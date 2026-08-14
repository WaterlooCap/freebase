import { PLUGIN_LOGO_ICON_COMPONENTS } from "metabase/plugins";
import MetabaseSettings from "metabase/utils/settings";

interface LogoIconProps {
  width?: number;
  height?: number;
  dark?: boolean;
  fill?: string;
}

/**
 * Renders the custom brand logo from our own `wc-brand-logo-url` setting.
 *
 * Registered into the OSS PLUGIN_LOGO_ICON_COMPONENTS registry, which LogoIcon already
 * consumes. We never read Metabase's gated `application-logo-url`.
 */
const WcBrandLogoIcon = ({ height = 32, width }: LogoIconProps) => {
  const logoUrl = MetabaseSettings.get("wc-brand-logo-url");
  const brandName = MetabaseSettings.get("wc-brand-name") ?? "Logo";

  if (!logoUrl) {
    return null;
  }

  return (
    <img
      src={logoUrl}
      alt={brandName}
      height={height}
      width={width}
      data-testid="main-logo"
    />
  );
};

// Only take over the registry when a custom logo is actually configured; otherwise
// LogoIcon falls back to DefaultLogoIcon.
if (MetabaseSettings.get("wc-brand-logo-url")) {
  PLUGIN_LOGO_ICON_COMPONENTS.push(WcBrandLogoIcon);
}
