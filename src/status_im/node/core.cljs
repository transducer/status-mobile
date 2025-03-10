(ns status-im.node.core
  (:require [re-frame.core :as re-frame]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.native-module.core :as status]
            [status-im.utils.config :as config]
            [status-im.utils.fx :as fx]
            [status-im.utils.platform :as utils.platform]
            [status-im.utils.types :as types]))

(defn- add-custom-bootnodes [config network all-bootnodes]
  (let [bootnodes (as-> all-bootnodes $
                    (get $ network)
                    (vals $)
                    (map :address $))]
    (if (seq bootnodes)
      (assoc-in config [:ClusterConfig :BootNodes] bootnodes)
      config)))

(defn- add-log-level [config log-level]
  (if (empty? log-level)
    (assoc config
           :MaxPeers 20
           :MaxPendingPeers 20
           :LogLevel "ERROR"
           :LogEnabled false)
    (assoc config
           :MaxPeers 20
           :MaxPendingPeers 20
           :LogLevel log-level
           :LogEnabled true)))

(defn get-network-genesis-hash-prefix
  "returns the hex representation of the first 8 bytes of
  a network's genesis hash"
  [network]
  (case network
    1 "d4e56740f876aef8"
    3 "41941023680923e0"
    4 "6341fd3daf94b748"
    nil))

(defn get-les-topic
  "returns discovery v5 topic derived from genesis of the provided network"
  [network]
  (let [les-discovery-identifier "LES2@"
        hash-prefix (get-network-genesis-hash-prefix network)]
    (when hash-prefix
      (str les-discovery-identifier hash-prefix))))

(defn get-topics
  [network]
  (let [les-topic (get-les-topic network)]
    (cond-> {"whisper" {:Min 2, :Max 2}}
      les-topic (assoc les-topic {:Min 2, :Max 2}))))

(defn- get-base-node-config [config]
  (cond-> (assoc config
                 :Name "StatusIM")
    config/dev-build?
    (assoc :ListenAddr ":30304"
           :DataDir (str (:DataDir config) "_dev"))))

(def login-node-config
  {:WalletConfig (cond-> {:Enabled true}
                   (not= config/opensea-api-key "")
                   (assoc :OpenseaAPIKey config/opensea-api-key))})

(defn- get-login-node-config [config]
  (merge config login-node-config))

(defn- pick-nodes
  "Picks `limit` different nodes randomly from the list of nodes
  if there is more than `limit` nodes in the list, otherwise return the list
  of nodes"
  [limit nodes]
  (take limit (shuffle nodes)))

(defn fleets [{:keys [custom-fleets]}]
  (as-> [(js/require "./fleets.js")] $
    (mapv #(:fleets (types/json->clj %)) $)
    (conj $ custom-fleets)
    (reduce merge $)))

(defn current-fleet-key [db]
  (keyword (get-in db [:multiaccount :fleet]
                   config/fleet)))

(defn get-current-fleet
  [db]
  (get (fleets db)
       (current-fleet-key db)))

(defn- get-multiaccount-node-config
  [{:keys [multiaccount :networks/networks :networks/current-network]
    :as db}]
  (let [wakuv2-config (get multiaccount :wakuv2-config {})
        current-fleet-key (current-fleet-key db)
        current-fleet (get-current-fleet db)
        wakuv2-enabled (boolean (:waku current-fleet))
        wakuv2-nodes (into (vals (:waku current-fleet)) (vals (get wakuv2-config :CustomNodes)))
        rendezvous-nodes (pick-nodes 3 (vals (:rendezvous current-fleet)))
        {:keys [installation-id log-level
                waku-bloom-filter-mode
                custom-bootnodes custom-bootnodes-enabled?]} multiaccount
        use-custom-bootnodes (get custom-bootnodes-enabled? current-network)]
    (cond-> (get-in networks [current-network :config])
      :always
      (get-base-node-config)

      :always
      (get-login-node-config)

      current-fleet
      (assoc :NoDiscovery   wakuv2-enabled
             :Rendezvous    (if wakuv2-enabled false (boolean (seq rendezvous-nodes)))
             :ClusterConfig {:Enabled true
                             :Fleet              (name current-fleet-key)
                             :BootNodes
                             (if wakuv2-enabled [] (pick-nodes 4 (vals (:boot current-fleet))))
                             :TrustedMailServers
                             (if wakuv2-enabled [] (pick-nodes 6 (vals (:mail current-fleet))))
                             :StaticNodes
                             (if wakuv2-enabled []
                                 (into (pick-nodes 2
                                                   (vals (:whisper current-fleet)))
                                       (vals (:static current-fleet))))
                             :RelayNodes wakuv2-nodes
                             :StoreNodes wakuv2-nodes
                             :RendezvousNodes    (if wakuv2-enabled [] rendezvous-nodes)})

      :always
      (assoc :LocalNotificationsConfig {:Enabled true}
             :BrowsersConfig {:Enabled true}
             :PermissionsConfig {:Enabled true}
             :MailserversConfig {:Enabled true}
             :EnableNTPSync true
             :WakuConfig
             {:Enabled true
              :BloomFilterMode waku-bloom-filter-mode
              :LightClient true
              :MinimumPoW 0.000001}
             :WakuV2Config (assoc wakuv2-config :Enabled wakuv2-enabled :Host "0.0.0.0")
             :ShhextConfig
             {:BackupDisabledDataDir      (utils.platform/no-backup-directory)
              :InstallationID             installation-id
              :MaxMessageDeliveryAttempts config/max-message-delivery-attempts
              :MailServerConfirmations    config/mailserver-confirmations-enabled?
              :VerifyTransactionURL       config/verify-transaction-url
              :VerifyENSURL               config/verify-ens-url
              :VerifyENSContractAddress   config/verify-ens-contract-address
              :VerifyTransactionChainID   config/verify-transaction-chain-id
              :DataSyncEnabled            true
              :PFSEnabled                 true}
             :RequireTopics (get-topics current-network)
             :StatusAccountsConfig {:Enabled true})

      (and
       config/bootnodes-settings-enabled?
       use-custom-bootnodes)
      (add-custom-bootnodes current-network custom-bootnodes)

      :always
      (add-log-level log-level))))

(defn get-new-config
  [db]
  (types/clj->json (get-multiaccount-node-config db)))

(fx/defn save-new-config
  "Saves a new status-go config for the current account
   This RPC method is the only way to change the node config of an account.
   NOTE: it is better used indirectly through `prepare-new-config`,
    which will take care of building up the proper config based on settings in
app-db"
  {:events [::save-new-config]}
  [{:keys [db]} config {:keys [on-success]}]
  {::json-rpc/call [{:method "settings_saveSetting"
                     :params [:node-config config]
                     :on-success on-success}]})

(fx/defn prepare-new-config
  "Use this function to apply settings to the current account node config"
  [{:keys [db]} {:keys [on-success]}]
  (let [key-uid (get-in db [:multiaccount :key-uid])]
    {::prepare-new-config [key-uid
                           (get-new-config db)
                           #(re-frame/dispatch
                             [::save-new-config % {:on-success on-success}])]}))

(re-frame/reg-fx
 ::prepare-new-config
 (fn [[key-uid config callback]]
   (status/prepare-dir-and-update-config key-uid config callback)))
