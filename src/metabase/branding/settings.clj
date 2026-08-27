(ns metabase.branding.settings
  "Waterloo branding settings.

  These are OUR settings, not Metabase's. Metabase's own `application-*` settings in
  [[metabase.appearance.settings]] are gated behind `:feature :whitelabel` and we do not
  touch them — that gate stays intact and functional, guarding settings we simply do not
  use.

  These settings carry no `:feature` gate because they are original code, fed into the
  AGPL theming pipeline (which already accepts arbitrary colors as a parameter). See
  test/metabase/branding/anti_bypass_test.clj, which enforces this boundary."
  (:require
   [metabase.settings.core :as setting :refer [defsetting]]
   [metabase.util.i18n :refer [deferred-tru]]))

(set! *warn-on-reflection* true)

(defsetting wc-brand-name
  (deferred-tru "Product name shown throughout the app.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :default    "Metabase"
  :audit      :getter)

(defsetting wc-brand-logo-url
  (deferred-tru "URL or data URI for the logo shown in the nav bar and on the login page.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter)

(defsetting wc-brand-favicon-url
  (deferred-tru "URL or data URI for the browser tab favicon.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter)

(defsetting wc-brand-colors
  (deferred-tru "Brand color overrides, as a map of color key to hex value.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :json
  :default    {}
  :audit      :getter)

(defsetting wc-help-link
  (deferred-tru "Help link behavior: \"metabase\", \"custom\", or \"hidden\".")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :default    "metabase"
  :audit      :getter)

(defsetting wc-help-link-destination
  (deferred-tru "Custom help link destination, used when wc-help-link is \"custom\".")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter)
