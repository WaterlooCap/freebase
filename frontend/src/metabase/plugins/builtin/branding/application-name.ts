import { PLUGIN_SELECTORS } from "metabase/plugins";
import type { State } from "metabase/redux/store";
import { getSetting } from "metabase/selectors/settings";

// eslint-disable-next-line metabase/no-literal-metabase-strings -- fallback when wc-brand-name is unset
const DEFAULT_APPLICATION_NAME = "Metabase";

/**
 * Overrides the OSS default (`PLUGIN_SELECTORS.getApplicationName`, which always returns the
 * literal "Metabase") with our own ungated `wc-brand-name` setting. We never read Metabase's
 * gated `application-name`.
 */
export function getApplicationName(state: State) {
  return getSetting(state, "wc-brand-name") || DEFAULT_APPLICATION_NAME;
}

PLUGIN_SELECTORS.getApplicationName = getApplicationName;
