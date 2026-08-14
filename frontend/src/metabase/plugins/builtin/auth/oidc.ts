import {
  PLUGIN_AUTH_PROVIDERS,
  PLUGIN_IS_PASSWORD_USER,
} from "metabase/plugins";
import MetabaseSettings from "metabase/utils/settings";

PLUGIN_AUTH_PROVIDERS.providers.push((providers) => {
  const oidcProvider = {
    name: "oidc",
    // circular dependencies
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    Button: require("metabase/auth/components/OidcButton").OidcButton,
  };

  return MetabaseSettings.get("free-oidc-enabled")
    ? [oidcProvider, ...providers]
    : providers;
});

PLUGIN_IS_PASSWORD_USER.push((user) => user.sso_source !== "oidc");
