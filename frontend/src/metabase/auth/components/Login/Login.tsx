import type { Location } from "history";
import { t } from "ttag";
import _ from "underscore";

import { usePageTitle } from "metabase/hooks/use-page-title";
import type { AuthProvider } from "metabase/plugins/types";
import { useSelector } from "metabase/redux";
import { getApplicationName } from "metabase/selectors/whitelabel";
import { Box, Divider, Stack, Text, Title } from "metabase/ui";

import { getAuthProviders } from "../../selectors";
import { AuthLayout } from "../AuthLayout";

interface LoginQueryString {
  redirect?: string;
}

interface LoginQueryParams {
  provider?: string;
}

interface LoginProps {
  params?: LoginQueryParams;
  location?: Location<LoginQueryString>;
}

export const Login = ({ params, location }: LoginProps): JSX.Element => {
  const providers = useSelector(getAuthProviders);
  const selection = getSelectedProvider(providers, params?.provider);
  const redirectUrl = location?.query?.redirect;
  const applicationName = useSelector(getApplicationName);

  usePageTitle(t`Login`);

  const [passwordProviders, otherProviders] = _.partition(
    providers,
    (provider) => provider.name === "password",
  );
  return (
    <AuthLayout>
      <Stack gap="0.5rem" ta="center">
        <Title order={1} c="text-primary" fz="1.5rem" lh="2rem">
          {t`Sign in to ${applicationName}`}
        </Title>
        {!selection && (
          <Text c="text-secondary">{t`Use your ${applicationName} account to continue.`}</Text>
        )}
      </Stack>
      {selection && selection.Panel && (
        <Box mt="2rem">
          <selection.Panel redirectUrl={redirectUrl} />
        </Box>
      )}
      {!selection && (
        <Stack mt="2rem" gap="1rem">
          {otherProviders.map((provider) => (
            <provider.Button
              key={provider.name}
              isCard={true}
              redirectUrl={redirectUrl}
            />
          ))}
          {otherProviders.length > 0 && passwordProviders.length > 0 && (
            <Divider label={t`or`} labelPosition="center" />
          )}
          {passwordProviders.map((provider) => (
            <Box key={provider.name} ta="center" fz="sm">
              <provider.Button redirectUrl={redirectUrl} />
            </Box>
          ))}
        </Stack>
      )}
    </AuthLayout>
  );
};

const getSelectedProvider = (
  providers: AuthProvider[],
  providerName?: string,
): AuthProvider | undefined => {
  const provider =
    providers.length > 1
      ? providers.find((p) => p.name === providerName)
      : providers[0];

  return provider?.Panel ? provider : undefined;
};
