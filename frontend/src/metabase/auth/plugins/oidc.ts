import { OidcButton } from "metabase/auth/components/OidcButton";
import {
  PLUGIN_AUTH_PROVIDERS,
  PLUGIN_IS_PASSWORD_USER,
} from "metabase/plugins";
import MetabaseSettings from "metabase/utils/settings";

PLUGIN_AUTH_PROVIDERS.providers.push((providers) => {
  const oidcProvider = {
    name: "oidc",
    Button: OidcButton,
  };

  return MetabaseSettings.get("free-oidc-enabled")
    ? [oidcProvider, ...providers]
    : providers;
});

PLUGIN_IS_PASSWORD_USER.push((user) => user.sso_source !== "oidc");
