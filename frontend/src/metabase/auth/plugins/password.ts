import { PasswordButton } from "metabase/auth/components/PasswordButton";
import { PasswordPanel } from "metabase/auth/components/PasswordPanel";
import { PLUGIN_AUTH_PROVIDERS } from "metabase/plugins";
import MetabaseSettings from "metabase/utils/settings";

PLUGIN_AUTH_PROVIDERS.providers.push((providers) => {
  if (!MetabaseSettings.get("enable-password-login")) {
    return providers;
  }

  const passwordProvider = {
    name: "password",
    Button: PasswordButton,
    Panel: PasswordPanel,
  };

  return [...providers, passwordProvider];
});
