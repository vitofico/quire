import { useEffect, useState } from "react";
import {
  type AiConfig, type DiscoveryRequest, type Preferences, type RequestMessage, discoveryJson, requestMessage,
} from "./api";

type Settings = { config: AiConfig; preferences: Preferences };
type State = { settings?: Settings; error?: RequestMessage; loading?: boolean };
type Subscription = (state: State) => void;
type AccountSettings = { state: State; subscribers: Set<Subscription> };
const accounts = new WeakMap<DiscoveryRequest, AccountSettings>();

function account(request: DiscoveryRequest): AccountSettings {
  const existing = accounts.get(request);
  if (existing) return existing;
  const created = { state: {}, subscribers: new Set<Subscription>() };
  accounts.set(request, created);
  return created;
}

function update(accountState: AccountSettings, state: State): void {
  accountState.state = state;
  accountState.subscribers.forEach((subscribe) => subscribe(state));
}

export async function refreshAiState(request: DiscoveryRequest): Promise<void> {
  const current = account(request);
  if (current.state.loading) return;
  update(current, { ...current.state, loading: true, error: undefined });
  try {
    const config = await discoveryJson<AiConfig>(request, "/ai/v1/config");
    const preferences = config.configured
      ? await discoveryJson<Preferences>(request, "/ai/v1/preferences")
      : { ai_enabled: false, style: { language: "auto", tone: "neutral" }, provider_id: "default" };
    update(current, { settings: { config, preferences } });
  } catch (error) {
    update(current, { error: requestMessage(error) });
  }
}

export function applyAiPreferences(request: DiscoveryRequest, preferences: Preferences): void {
  const current = account(request);
  const settings = current.state.settings;
  if (settings) update(current, { settings: { ...settings, preferences } });
}

export function useAiState(request?: DiscoveryRequest, available = true): State {
  const [state, setState] = useState<{ request: DiscoveryRequest; value: State }>();
  useEffect(() => {
    if (!request || !available) return;
    const current = account(request);
    function subscribe(value: State) {
      setState({ request: request!, value });
    }
    current.subscribers.add(subscribe);
    subscribe(current.state);
    void refreshAiState(request);
    return () => { current.subscribers.delete(subscribe); };
  }, [request, available]);
  return available && request && state?.request === request ? state.value : {};
}

export function missingProvider(config: AiConfig, preferences: Preferences): boolean {
  return preferences.provider_id !== "default"
    && !(config.providers ?? []).some((provider) => provider.id === preferences.provider_id);
}
