(ns metabase.branding.api-test
  (:require
   [clojure.test :refer :all]
   [metabase.appearance.settings :as appearance.settings]
   [metabase.branding.settings :as branding.settings]
   [metabase.test :as mt]))

(deftest requires-superuser-test
  (testing "non-admins cannot change branding"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :put 403 "branding/settings"
                                 {:wc-brand-name "Pwned"})))))

(deftest admin-can-set-branding-test
  (testing "an admin can set the brand name and colors"
    (mt/with-temporary-setting-values [wc-brand-name nil, wc-brand-colors {}]
      (mt/user-http-request :crowberto :put 200 "branding/settings"
                            {:wc-brand-name   "Waterloo"
                             :wc-brand-colors {"brand" "#3E90C5"}})
      (is (= "Waterloo" (branding.settings/wc-brand-name)))
      (is (= {:brand "#3E90C5"} (branding.settings/wc-brand-colors))))))

(deftest closed-schema-rejects-unknown-keys-test
  (testing "the endpoint's body schema is closed: a superuser cannot smuggle in unrelated settings"
    (mt/with-temporary-setting-values [application-name "Metabase"]
      (mt/user-http-request :crowberto :put 400 "branding/settings"
                            {:wc-brand-name    "x"
                             :application-name "EVIL"})
      (is (= "Metabase" (appearance.settings/application-name))
          "a rejected request must never reach set-many!, so application-name stays unchanged"))))
