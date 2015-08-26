(ns riemann.plugin.kafka
  "A riemann plugin to consume and produce from and to a kafka queue"
  (:use [clojure.tools.logging :only (info error debug warn)])
  (:import com.aphyr.riemann.Proto$Msg
           kafka.consumer.KafkaStream
           kafka.producer.KeyedMessage)
  (:require [riemann.core          :refer [stream!]]
            [riemann.common        :refer [decode-msg encode]]
            [clj-kafka.core        :refer [to-clojure]]
            [clj-kafka.consumer.zk :refer [consumer]]
            [clj-kafka.producer    :refer [send-message producer]]
            [riemann.service       :refer [Service ServiceEquiv]]
            [riemann.config        :refer [service!]]
            [clojure.tools.logging :refer [info error]]
            [clj-json.core :as json]
            [clj-time.format]
            [clj-time.core]
            [clj-time.coerce]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [riemann.streams :as streams]))

(defn safe-decode
  "Do not let a bad payload break our consumption"
  [input]
  (try
    (let [{:keys [value]} (to-clojure input)]
      (decode-msg (Proto$Msg/parseFrom value)))
    (catch Exception e
      (error e "could not decode protobuf msg"))))

(defn stringify
  "Prepare a map to be converted to properties"
  [props]
  (let [input (dissoc props :topic)
        skeys (map (juxt (comp name key) val) input)]
    (reduce merge {} skeys)))

(defn start-kafka-thread
  "Start a kafka thread which will pop messages off of the queue as long
   as running? is true"
  [running? core {:keys [topic] :as config}]
  (let [inq (consumer (stringify config))]
    (future
      (info "in consumption thread with consumer: " inq)
      (try
        (let [stream-map   (.createMessageStreams inq {topic (int 1)})
              [stream & _] (get stream-map topic)
              msg-seq      (iterator-seq (.iterator ^KafkaStream stream))]
          (doseq [msg msg-seq :while @running? :when @core]
            (doseq [event (:events (safe-decode msg))]
              (info "got input event: " event)
              (stream! @core event))
            (.commitOffsets inq))
          (info "was instructed to stop, BYE!"))
        (catch Exception e
          (error e "interrupted consumption"))
        (finally
          (.shutdown inq))))))

(defn kafka-consumer
  "Yield a kafka consumption service"
  [config]
  (service!
   (let [running? (atom true)
         core     (atom nil)]
     (reify
       clojure.lang.ILookup
       (valAt [this k not-found]
         (or (.valAt this k) not-found))
       (valAt [this k]
         (info "looking up: " k)
         (if (= (name k) "config") config))
       ServiceEquiv
       (equiv? [this other]
         (= config (:config other)))
       Service
       (conflict? [this other]
         (= config (:config other)))
       (start! [this]
         (info "starting kafka consumer running for topics: "
               (:topic config))
         (start-kafka-thread running? core
                             (merge {:topic "riemann"} config)))
       (reload! [this new-core]
         (info "reload called, setting new core value")
         (reset! core new-core))
       (stop! [this]
         (reset! running? false)
         (info "kafka consumer stopping"))))))

(def ^{:private true} format-iso8601
  (clj-time.format/with-zone (clj-time.format/formatters :date-time)
    clj-time.core/utc))

(defn ^{:private true} iso8601 [event-s]
  (clj-time.format/unparse format-iso8601
                           (clj-time.coerce/from-long (* 1000 event-s))))

(defn ^{:private true} safe-iso8601 [event-s]
  (try (iso8601 event-s)
    (catch Exception e
      (warn "Unable to parse iso8601 input: " event-s)
      (clj-time.format/unparse format-iso8601 (clj-time.core/now)))))

(defn ^{:private true} stashify-timestamp [event]
  (->  (if-not (get event "@timestamp")
         (let [isotime (:isotime event)]
           (if-not isotime
             (let [time (:time event)]
               (assoc event "@timestamp" (safe-iso8601 (long time))))
             (assoc event "@timestamp" isotime)))
         event)
       (dissoc :isotime)
       (dissoc :time)
       (dissoc :ttl)))

(defn ^{:private true} edn-safe-read [v]
  (try
    (edn/read-string v)
    (catch Exception e
      (warn "Unable to read supposed EDN form with value: " v)
      v)))

(defn ^{:private true} massage-event [event]
  (into {}
        (for [[k v] event
              :when v]
          (cond
           (= (name k) "_id") [k v]
           (.startsWith (name k) "_")
           [(.substring (name k) 1) (edn-safe-read v)]
           :else
           [k v]))))

(defn ^{:private true} elastic-event [event massage]
  (let [e (-> event
              stashify-timestamp)]
    (if massage
      (massage-event e)
      e)))

(defn ^{:private true} riemann-to-elasticsearch [events massage]
  (->> [events]
       flatten
       (remove streams/expired?)
       (map #(elastic-event % massage))))

(defn kafka-producer
  "Yield a kafka producer"
  [{:keys [topic] :as config}]
  (let [p (producer (stringify config))]
    (fn [event]
      (let [events (if (sequential? event) event [event])]
        (doseq [message (riemann-to-elasticsearch events true)] (send-message p (KeyedMessage. topic (.getBytes (json/generate-string message) "utf-8"))))))))
