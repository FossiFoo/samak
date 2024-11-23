(ns samak.packet
  (:require [samak.tools :as tools]
            [samak.helpers :as helpers]))

(def status {:ok ::status_ok
             :error ::status_error})

(defn make-packet
  ([type content] (make-packet(helpers/uuid) (:ok status) type content))
  ([id stat type content]
   "Defines the 'wire' protocol for samak messages, internally and between runtimes."
   (merge {::status stat
           ::id id
           ::body {::type type
                   ::content content}})))

(defn make-error
  ([type content]
   "Creates a packet indicating an error."
   (make-packet (helpers/uuid) (:error status) type content)))

(defn get-id
  [msg]
  "Gets the uuid of this packet."
  (::id msg))

(defn get-body
  [msg]
  "Get the content of the message if status is ok."
  (when (= (::status msg) (:ok status))
    (::body msg)))

(defn get-type
  [msg]
  "Gets just the type of the message, i.e. what kind of receiver is expected."
  (get-in msg [::body ::type]))

(defn get-type-content
  [type msg]
  "Get the content of a valid message if the type matches."
  (when-let [body (get-body msg)]
    (when (= (::type body) type)
      (::content body))))

(defn assert-type-content
  [type msg]
  "Asserts a valid message of the type. Logs an error otherwise."
  (or (get-type-content type msg)
      (tools/log (str "error deserializing runtime message:" msg))))
