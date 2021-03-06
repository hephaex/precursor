(ns pc.http.routes.api
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.core.memoize :as memo]
            [clojure.string :as str]
            [clojure.tools.reader.edn :as edn]
            [crypto.equality :as crypto]
            [defpage.core :as defpage :refer (defpage)]
            [pc.auth :as auth]
            [pc.crm :as crm]
            [pc.datomic :as pcd]
            [pc.early-access]
            [pc.http.doc :as doc-http]
            [pc.http.team :as team-http]
            [pc.http.handlers.custom-domain :as custom-domain]
            [pc.models.chat-bot :as chat-bot-model]
            [pc.models.doc :as doc-model]
            [pc.models.flag :as flag-model]
            [pc.models.team :as team-model]
            [pc.profile :as profile]
            [ring.middleware.anti-forgery :as csrf]
            [slingshot.slingshot :refer (try+ throw+)]))

(defpage new [:post "/api/v1/document/new"] [req]
  (let [params (some-> req :body slurp edn/read-string)
        read-only? (:read-only params)
        doc-name (:document/name params)]
    (if-not (:subdomain req)
      (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
            intro-layers? (:intro-layers? params)
            doc (doc-model/create-public-doc!
                 (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                        (when cust-uuid {:document/creator cust-uuid})
                        (when read-only? {:document/privacy :document.privacy/read-only})
                        (when doc-name {:document/name doc-name})))]
        (when intro-layers?
          (doc-http/add-intro-layers doc))
        {:status 200 :body (pr-str {:document (doc-model/read-api doc)})})
      (if (and (:team req)
               (auth/logged-in? req)
               (auth/has-team-permission? (pcd/default-db) (:team req) (:auth req) :admin))
        (let [doc (doc-model/create-team-doc!
                   (:team req)
                   (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                          (when-let [cust-uuid (get-in req [:cust :cust/uuid])]
                            {:document/creator cust-uuid})
                          (when read-only?
                            {:document/privacy :document.privacy/read-only})
                          (when doc-name
                            {:document/name doc-name})))]
          {:status 200 :body (pr-str {:document (doc-model/read-api doc)})})
        {:status 400 :body (pr-str {:error :unauthorized-to-team
                                    :redirect-url (str (url/map->URL {:host (profile/hostname)
                                                                      :protocol (if (profile/force-ssl?)
                                                                                  "https"
                                                                                  (name (:scheme req)))
                                                                      :port (if (profile/force-ssl?)
                                                                              (profile/https-port)
                                                                              (profile/http-port))
                                                                      :path "/new"
                                                                      :query (:query-string req)}))
                                    :msg "You're unauthorized to make documents in this subdomain. Please request access."})}))))

(defpage create-team [:post "/api/v1/create-team"] [req]
  (let [params (some-> req :body slurp edn/read-string)
        subdomain (some-> params :subdomain str/lower-case str/trim)
        coupon-code (some-> params :coupon-code)
        cust (get-in req [:auth :cust])]
    (cond (empty? cust)
          {:status 400 :body (pr-str {:error :not-logged-in
                                      :msg "You must log in first."})}

          (empty? subdomain)
          {:status 400 :body (pr-str {:error :missing-subdomain
                                      :msg "Subdomain is missing."})}

          (not (custom-domain/valid-subdomain? subdomain))
          {:status 400 :body (pr-str {:error :subdomain-exists
                                      :msg "Sorry, that subdomain is taken. Please try another."})}

          (seq (team-model/find-by-subdomain (pcd/default-db) subdomain))
          {:status 400 :body (pr-str {:error :subdomain-exists
                                      :msg "Sorry, that subdomain is taken. Please try another."})}

          :else
          (try+
           (let [team (team-http/setup-new-team subdomain cust coupon-code)]
             {:status 200 :body (pr-str {:team (team-model/read-api team)})})
           (catch [:error :subdomain-exists] e
             {:status 400 :body (pr-str {:error :subdomain-exists
                                         :msg "Sorry, that subdomain is taken. Please try another."})})))))

(defpage early-access [:post "/api/v1/early-access"] [req]
  (if-let [cust (get-in req [:auth :cust])]
    (do
      (pc.early-access/create-request cust (edn/read-string (slurp (:body req))))
      (pc.early-access/approve-request cust)
      {:status 200 :body (pr-str {:msg "Thanks!" :access-request-granted? true})})
    {:status 401 :body (pr-str {:error :not-logged-in
                                :msg "Please log in to request early access."})}))

(defpage create-solo-trial [:post "/api/v1/create-solo-trial"] [req]
  (if-let [cust (get-in req [:auth :cust])]
    (do
      (flag-model/add-flag cust :flags/private-docs)
      {:status 200 :body (pr-str {:msg "Thanks!" :solo-plan-created? true})})
    {:status 401 :body (pr-str {:error :not-logged-in
                                :msg "Please log in to request early access."})}))

(def get-dribbble-profile (memo/ttl crm/get-dribbble-profile :ttl/threshold (* 1000 60 60)))

(def dribbble-user-whitelist #{"lobanovskiy"
                               "dhotlo2"
                               "fatihturan"
                               "kenseals"
                               "jyo208"
                               "tooks_"
                               "dannykingme"})

;; proxy for the dribbble API, to be used by our dribbble cards on the blog
(defpage dribbble-profile "/api/v1/dribbble/users/:username" [req]
  (let [username (get-in req [:params :username])]
    (if (contains? dribbble-user-whitelist username)
      {:body (json/encode (get-dribbble-profile username))
       :status 200
       :headers {"Content-Type" "application/json; charset=utf-8"}}
      (throw+ {:status 400
               :public-message "Sorry, this user isn't on the whitelist."}))))

(def app (defpage/collect-routes))
