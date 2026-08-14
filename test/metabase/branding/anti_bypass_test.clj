(ns metabase.branding.anti-bypass-test
  "Proves our branding is a REWRITE, not a re-gated bypass.

  This fork exists to stop using Metabase's enterprise features without a license.
  Branding is the one place where the shortcut is genuinely tempting: deleting
  `:feature :whitelabel` from src/metabase/appearance/settings.clj would restore the
  Waterloo branding in about five minutes, and would be exactly the same act as the
  token_check.clj bypass we removed -- just scoped to one feature.

  These tests are the executable form of that boundary. If they fail, someone took the
  shortcut. Do not 'fix' them by relaxing the assertions."
  (:require
   [clojure.test :refer :all]
   [metabase.appearance.settings :as appearance.settings]
   [metabase.branding.settings :as branding.settings]
   [metabase.premium-features.core :as premium-features]
   [metabase.settings.core :as setting]
   [metabase.test :as mt]))

(deftest metabase-whitelabel-gate-is-intact-test
  (testing "Metabase's own application-name is STILL GATED and returns the stock default"
    (mt/with-temporary-setting-values [premium-embedding-token nil]
      ;; Store a real value in THEIR setting, exactly as prod has today.
      (setting/set-value-of-type! :string :application-name "Waterloo")
      (is (= "Metabase" (appearance.settings/application-name))
          (str "Metabase's application-name must still return the stock default without a "
               "token. If this returns \"Waterloo\", someone deleted :feature :whitelabel "
               "-- that is the bypass, and it must be reverted.")))))

(deftest our-branding-works-without-a-token-test
  (testing "OUR setting carries the branding, with no token and no gate"
    (mt/with-temporary-setting-values [premium-embedding-token nil
                                       wc-brand-name           "Waterloo"]
      (is (= "Waterloo" (branding.settings/wc-brand-name))
          "Our own setting is ungated original code and must just work."))))

(deftest whitelabel-feature-remains-unlicensed-test
  (testing "we never claim the whitelabel feature"
    (mt/with-temporary-setting-values [premium-embedding-token nil]
      (is (false? (boolean (premium-features/has-feature? :whitelabel)))
          "We must not hold the whitelabel feature. Note this assertion alone does NOT
           prove a rewrite -- deleting the gate leaves this false too. The load-bearing
           test is metabase-whitelabel-gate-is-intact-test above."))))

(deftest appearance-settings-still-declare-the-feature-gate-test
  (testing "the :feature :whitelabel declarations are still present in the source"
    (let [source (slurp "src/metabase/appearance/settings.clj")
          gate-count (count (re-seq #":feature\s+:whitelabel" source))]
      (is (>= gate-count 10)
          (str "Expected the :feature :whitelabel gates to still be declared in "
               "src/metabase/appearance/settings.clj (found " gate-count "). If this "
               "dropped, someone removed Metabase's license checks. Revert it.")))))
