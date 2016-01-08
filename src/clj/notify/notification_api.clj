(ns notify.notification-api
  (:require [castra.core :refer [defrpc *session*]]
            [clojure.data.priority-map :refer :all])
  (:import [java.util UUID Comparator]))

(defonce max-sessions (atom 1000))
(defn get-max-sessions [] @max-sessions)
(defn set-max-sessions! [new-max] (reset! max-sessions new-max))

(defonce aged-sessions (atom (priority-map-keyfn first)))

(defn add-session [pm session-id timestamp ack notifications]
  (assoc pm session-id [timestamp ack notifications]))
(defn sessions-count [] (count @aged-sessions))
(defn get-timestamp [pm session-id]
  (get-in pm [0 session-id]))
(defn assoc-timestamp [pm session-id timestamp]
  (assoc-in pm [0 session-id] timestamp))
(defn get-ack [pm session-id]
  (get-in pm [1 session-id]))
(defn assoc-ack [pm session-id ack]
  (assoc-in pm [1 session-id] ack))
(defn get-unacked-notifications [pm session-id]
  (get-in pm [2 session-id]))
(defn assoc-unacked-notifications [pm session-id notifications]
  (assoc-in pm [2 session-id] notifications))

(defn add-notification!
  "Adds a notification [type value] to the list of notifications to be sent to a given session.
   Notification identifiers are assigned in ascending order on a per session basis."
  [session-id type value]
  (swap!
    aged-sessions
    (fn [pm]
      (if (contains? pm session-id)
        (let [new-ack (+ 1 (get-ack pm session-id))
              pm (assoc-ack pm session-id new-ack)
              unacked-sesion-notifications (get-unacked-notifications pm session-id)
              unacked-sesion-notifications (assoc
                                             unacked-sesion-notifications
                                             new-ack
                                             {:notification-type type :value value})
              pm (assoc-unacked-notifications pm session-id unacked-sesion-notifications)]
          pm)
        (add-session pm session-id (System/currentTimeMillis) 0
                     {:notification-type type :value value})))))

(defn drop-acked-notifications
  "Remove all notifications with an ack <= last-ack"
  [pm session-id last-ack]
  (if (contains? pm session-id)
    (let [unacked-sesion-notifications (get-unacked-notifications pm session-id)
          unacked-sesion-notifications (reduce
                                         (fn [m k]
                                           (if (> k last-ack)
                                             m
                                             (dissoc m k)))
                                         unacked-sesion-notifications
                                         (keys unacked-sesion-notifications))
          pm (assoc-unacked-notifications pm session-id unacked-sesion-notifications)
          pm (assoc-ack pm session-id last-ack)]
      pm)))

(defn get-session-id
  "Returns the session id (a UUID string) assigned to the current session."
  [] (:session-id @*session*))

(defn identify-session!
  "Assign a unique random identifier (a UUID) to the current session, as needed.
   Returns true."
  []
  (if (nil? (:session-id @*session*))
    (swap! *session* assoc :session-id (.toString (UUID/randomUUID))))
  true)

(defrpc get-notifications
        "An rpc call to return all the new notifications for the current session."
        [last-ack & [session-id]]
        {:rpc/pre [(nil? session-id) (identify-session!)]}
        (let [session-id (or session-id (get-session-id))
              timestamp (System/currentTimeMillis)]
          (swap!
            aged-sessions
            (fn [pm]
              (if (contains? pm session-id)
                (let [pm (assoc-timestamp pm session-id timestamp)
                      pm (drop-acked-notifications pm session-id last-ack)]
                  pm)
                (add-session pm session-id timestamp last-ack {}))))
          (get-unacked-notifications @aged-sessions session-id)))
