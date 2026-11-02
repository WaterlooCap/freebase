import { useEffect } from "react";

import { useSetting } from "metabase/settings";

export const useFavicon = ({ favicon }: { favicon: string | null }) => {
  // Our own ungated branding setting, not Metabase's gated `application-favicon-url`.
  const defaultFavicon = useSetting("wc-brand-favicon-url");

  useEffect(() => {
    document
      .querySelector('link[rel="icon"]')
      ?.setAttribute("href", favicon ?? defaultFavicon);

    return () => {
      if (defaultFavicon) {
        document
          .querySelector('link[rel="icon"]')
          ?.setAttribute("href", defaultFavicon);
      }
    };
  }, [defaultFavicon, favicon]);
};
