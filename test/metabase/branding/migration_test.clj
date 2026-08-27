(ns metabase.branding.migration-test
  (:require
   [clojure.test :refer :all]
   [metabase.branding.migration :as branding.migration]
   [metabase.branding.settings :as branding.settings]
   [metabase.settings.core :as setting]
   [metabase.test :as mt]))

(deftest migrates-stored-application-values-test
  (testing "existing application-* values are copied to wc-brand-*"
    (mt/with-temporary-setting-values [wc-brand-name nil, wc-brand-colors {}]
      (setting/set-value-of-type! :string :application-name "Waterloo")
      (setting/set-value-of-type! :json :application-colors {"brand" "#3E90C5"})
      (branding.migration/migrate-application-settings!)
      (is (= "Waterloo" (branding.settings/wc-brand-name))
          "Reads the RAW stored value; the gated getter would return 'Metabase'.")
      ;; :type :json settings decode with keywordized keys (json/decode+kw), same as
      ;; metabase.appearance.settings/application-colors -- so a string key on the way in
      ;; comes back as a keyword.
      (is (= {:brand "#3E90C5"} (branding.settings/wc-brand-colors))))))

(deftest migration-is-idempotent-test
  (testing "re-running never clobbers an already-set brand value"
    (mt/with-temporary-setting-values [wc-brand-name "Already Set"]
      (setting/set-value-of-type! :string :application-name "Waterloo")
      (branding.migration/migrate-application-settings!)
      (is (= "Already Set" (branding.settings/wc-brand-name))
          "An operator's explicit choice must win over the migration."))))

(deftest migration-skips-unset-source-values-test
  (testing "an unset application-* value does not blank out its wc-brand-* counterpart"
    (mt/with-temporary-setting-values [wc-brand-name nil]
      (setting/set-value-of-type! :string :application-name nil)
      (branding.migration/migrate-application-settings!)
      (is (= "Metabase" (branding.settings/wc-brand-name))
          "Falls back to the default, not to nil."))))
