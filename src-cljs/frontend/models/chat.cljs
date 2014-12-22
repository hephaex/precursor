(ns frontend.models.chat
  (:require [datascript :as d]
            [frontend.utils :as utils :include-macros true]))

(defn chat-timestamps-since [db time]
  (map first
       (d/q '{:find [?server-timestamp]
              :in [$ ?last-time]
              :with [?t]
              :where [[?t :chat/body]
                      [?t :server/timestamp ?server-timestamp]
                      [(> ?server-timestamp ?last-time)]]}
            db time)))

(defn compute-unread-chat-count [db last-read-time]
  (if-not last-read-time
    0
    (count (chat-timestamps-since db last-read-time))))

(defn display-name [chat client-id]
  (or (:chat/cust-name chat)
      (if (= (str (:session/uuid chat))
             client-id)
        "You"
        (apply str (take 6 (str (:session/uuid chat)))))))
