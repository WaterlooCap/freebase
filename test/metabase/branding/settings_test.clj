(ns metabase.branding.settings-test
  (:require
   [clojure.test :refer :all]
   [metabase.branding.settings :as branding.settings]
   [metabase.test :as mt]))

(deftest brand-settings-are-not-feature-gated-test
  (testing "our branding settings work without any premium token"
    (mt/with-temporary-setting-values [premium-embedding-token nil
                                       wc-brand-name           "Waterloo"]
      (is (= "Waterloo" (branding.settings/wc-brand-name))
          "Our settings are ours: no token, no gate, they just work."))))

(deftest brand-name-defaults-to-metabase-test
  (testing "unset branding falls back to stock Metabase naming"
    (mt/with-temporary-setting-values [wc-brand-name nil]
      (is (= "Metabase" (branding.settings/wc-brand-name))))))

(deftest brand-colors-round-trip-test
  (testing "colors persist as a map"
    ;; :type :json settings decode with keywordized keys (json/decode+kw), same as
    ;; metabase.appearance.settings/application-colors -- so a string key on the way in
    ;; comes back as a keyword.
    (mt/with-temporary-setting-values [wc-brand-colors {"brand" "#3E90C5"}]
      (is (= {:brand "#3E90C5"} (branding.settings/wc-brand-colors))))))
