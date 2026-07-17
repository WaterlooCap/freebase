import { useCallback, useState } from "react";
import { t } from "ttag";
import _ from "underscore";

import {
  SettingsPageWrapper,
  SettingsSection,
} from "metabase/admin/components/SettingsSection";
import { getExtraFormFieldProps } from "metabase/admin/settings/utils";
import {
  useGetAdminSettingsDetailsQuery,
  useGetSettingsQuery,
  useUpdateFreeOidcMutation,
} from "metabase/api";
import { ConfirmModal } from "metabase/common/components/ConfirmModal";
import { LoadingAndErrorWrapper } from "metabase/common/components/LoadingAndErrorWrapper";
import {
  Form,
  FormErrorMessage,
  FormProvider,
  FormSubmitButton,
  FormTextInput,
} from "metabase/forms";
import { Box, Button, Flex, Stack } from "metabase/ui";
import type { Settings } from "metabase-types/api";

export type FreeOidcSettings = Pick<
  Settings,
  | "free-oidc-enabled"
  | "free-oidc-issuer-uri"
  | "free-oidc-client-id"
  | "free-oidc-client-secret"
  | "free-oidc-scopes"
>;

const DEFAULT_SCOPES = "openid email profile";

type OidcProbeStepResult = {
  error?: string;
};

type OidcProbeError = {
  data?: {
    message?: string;
    errors?: {
      discovery?: OidcProbeStepResult;
      credentials?: OidcProbeStepResult;
    };
  };
};

// The backend probes the provider before saving when enabling OIDC. On failure it
// responds with `{errors: {discovery: {...}, credentials: {...}}}`, where the nested
// maps (not plain strings) carry the human-readable `error`. Formik's stock error
// handling only understands `data.message` or `data.errors._error`, so we translate
// the probe result into a single message ourselves and surface it via
// `FormErrorMessage` (this is the UI's "test connection" behavior).
const getSubmitErrorMessage = (error: unknown): string => {
  const probeErrors = (error as OidcProbeError)?.data?.errors;
  const probeMessages = [
    probeErrors?.discovery?.error,
    probeErrors?.credentials?.error,
  ].filter((message): message is string => Boolean(message));

  if (probeMessages.length > 0) {
    return probeMessages.join(" ");
  }

  const genericMessage = (error as OidcProbeError)?.data?.message;
  if (typeof genericMessage === "string") {
    return genericMessage;
  }

  return t`Could not connect to the OIDC provider. Check the issuer URI, client ID, and client secret.`;
};

export const SettingsFreeOidcForm = () => {
  const { data: settingDetails } = useGetAdminSettingsDetailsQuery();
  const { data: settingValues } = useGetSettingsQuery();
  const [updateFreeOidc] = useUpdateFreeOidcMutation();
  const [isConfirmingDisable, setIsConfirmingDisable] = useState(false);
  const isEnabled = settingValues?.["free-oidc-enabled"];

  const handleSubmit = useCallback(
    async (values: Partial<FreeOidcSettings>) => {
      try {
        await updateFreeOidc({
          ...values,
          "free-oidc-enabled": true,
        }).unwrap();
      } catch (error) {
        throw { data: { message: getSubmitErrorMessage(error) } };
      }
    },
    [updateFreeOidc],
  );

  const handleDisable = useCallback(async () => {
    await updateFreeOidc({ "free-oidc-enabled": false }).unwrap();
    setIsConfirmingDisable(false);
  }, [updateFreeOidc]);

  if (!settingValues) {
    return <LoadingAndErrorWrapper loading />;
  }

  return (
    <SettingsPageWrapper title={t`OIDC`}>
      <FormProvider
        initialValues={getFormValues(settingValues)}
        onSubmit={handleSubmit}
        enableReinitialize
      >
        {({ dirty }) => (
          <Form>
            <SettingsSection>
              <Stack gap="md">
                <FormTextInput
                  name="free-oidc-issuer-uri"
                  label={t`Issuer URI`}
                  placeholder="https://sso.example.com/application/o/metabase/"
                  required
                  autoFocus
                  {...getExtraFormFieldProps(
                    settingDetails?.["free-oidc-issuer-uri"],
                  )}
                />
                <FormTextInput
                  name="free-oidc-client-id"
                  label={t`Client ID`}
                  required
                  {...getExtraFormFieldProps(
                    settingDetails?.["free-oidc-client-id"],
                  )}
                />
                <FormTextInput
                  name="free-oidc-client-secret"
                  label={t`Client Secret`}
                  type="password"
                  nullable
                  {...getExtraFormFieldProps(
                    settingDetails?.["free-oidc-client-secret"],
                  )}
                />
                <FormTextInput
                  name="free-oidc-scopes"
                  label={t`Scopes`}
                  nullable
                  {...getExtraFormFieldProps(
                    settingDetails?.["free-oidc-scopes"],
                  )}
                />
              </Stack>
              <Flex justify="end" align="center" gap="1rem">
                <Box>
                  <FormErrorMessage />
                </Box>
                {isEnabled && (
                  <Button
                    color="danger"
                    variant="subtle"
                    onClick={() => setIsConfirmingDisable(true)}
                  >{t`Disable`}</Button>
                )}
                <FormSubmitButton
                  disabled={!dirty}
                  label={isEnabled ? t`Save changes` : t`Save and enable`}
                  variant="filled"
                />
              </Flex>
            </SettingsSection>
          </Form>
        )}
      </FormProvider>
      <ConfirmModal
        opened={isConfirmingDisable}
        title={t`Disable OIDC?`}
        message={t`Users will no longer be able to sign in with your OIDC provider.`}
        confirmButtonText={t`Disable`}
        onConfirm={handleDisable}
        onClose={() => setIsConfirmingDisable(false)}
      />
    </SettingsPageWrapper>
  );
};

export const getFormValues = (
  allSettings: Partial<Settings>,
): Partial<FreeOidcSettings> => {
  const freeOidcSettings = _.pick(allSettings, [
    "free-oidc-issuer-uri",
    "free-oidc-client-id",
    "free-oidc-client-secret",
    "free-oidc-scopes",
  ]);

  return {
    ...freeOidcSettings,
    "free-oidc-scopes": freeOidcSettings["free-oidc-scopes"] || DEFAULT_SCOPES,
  };
};
