(ns metabase.branding.migration
  "One-time copy of Metabase's `application-*` branding values onto our `wc-brand-*`
  settings.

  Reads the RAW stored values via `setting/get-value-of-type` rather than the public
  getters: `application-name` and friends are gated behind `:feature :whitelabel`, so
  their getters return the stock default in an OSS build even when a real value is
  stored. We are copying data out of rows the operator already owns, not defeating the
  gate — the gate keeps working, and Metabase's settings keep returning their defaults."
  (:require
   ;; bare require, no symbols used: loading this namespace registers Metabase's
   ;; `application-*` settings so [[setting/get-value-of-type]] and
   ;; [[setting/db-stored-value]] can find them below, regardless of what else has
   ;; already booted.
   [metabase.appearance.core]
   [metabase.settings.core :as setting]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

(def ^:private setting-map
  "Source (Metabase, gated) -> destination (ours, ungated), with the value type."
  [[:application-name             :wc-brand-name             :string]
   [:application-logo-url         :wc-brand-logo-url         :string]
   [:application-favicon-url      :wc-brand-favicon-url      :string]
   [:application-colors           :wc-brand-colors           :json]
   [:help-link                    :wc-help-link              :string]
   [:help-link-custom-destination :wc-help-link-destination  :string]])

(defn- blank-value? [v]
  (or (nil? v)
      (and (string? v) (empty? v))
      (and (map? v) (empty? v))))

(defn- unset?
  "True if `k` has nothing genuinely persisted in the app DB for it, or what's stored
  decodes to a blank value.

  We can't rely on `get-value-of-type` alone to answer this: several of Metabase's own
  `application-*` settings ship with a non-blank compiled-in `:default` (e.g.
  `application-logo-url` defaults to the stock `\"app/assets/img/logo.svg\"` path, and
  our own `wc-brand-name` defaults to `\"Metabase\"`), so a setting that has never been
  written still reads back as a non-blank value via its getter. `db-stored-value` looks
  only at the app DB row and bypasses defaults (and env vars, user-/database-local
  values) entirely, so it tells us whether something was actually stored, as opposed to
  merely defaulted."
  [value-type k]
  (or (nil? (setting/db-stored-value k))
      (blank-value? (setting/get-value-of-type value-type k))))

(defn migrate-application-settings!
  "Copy any stored `application-*` branding values onto the `wc-brand-*` settings.

  Idempotent, and never overwrites a destination that already has a value — an
  operator's explicit choice wins over the migration."
  []
  (let [migrated (atom [])]
    (doseq [[src dest value-type] setting-map]
      (when (and (not (unset? value-type src))
                 (unset? value-type dest))
        (let [source-value (setting/get-value-of-type value-type src)]
          (setting/set-value-of-type! value-type dest source-value)
          (swap! migrated conj dest)
          (log/infof "Migrated branding setting %s -> %s" src dest))))
    (when (seq @migrated)
      (log/infof "Branding migration copied %d setting(s): %s" (count @migrated) (pr-str @migrated)))
    {:migrated @migrated}))
