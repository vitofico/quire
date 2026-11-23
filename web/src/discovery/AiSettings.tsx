import { english, interpolate, type Translate } from "./translation";
import { useState } from "react";
import {
  type AiConfig, type DiscoveryRequest, type Preferences, type Profile, type Recommendation,
  discoveryJson, isHttpStatus, jsonBody, publicLink, translateRequestMessage,
} from "./api";
import { applyAiPreferences, missingProvider, refreshAiState, useAiState } from "./aiState";
import { useAiOperation } from "./aiOperation";
import { type AiPanelProps, Citations } from "./Insights";

const toneOptions = { neutral: "Neutral", enthusiastic: "Enthusiastic", scholarly: "Scholarly", casual: "Casual" };

export function AiSettingsPanel({ request, aiAvailable, t = english }: AiPanelProps) {
  const { settings, loading, error } = useAiState(request, aiAvailable);
  return (
    <section className="discovery-panel panel">
      <h2>{t("AI settings")}</h2>
      {!request && <p>{t("Connect an account to manage AI features.")}</p>}
      {request && (aiAvailable === false || settings?.config.configured === false) && <p>{t("AI is unavailable on this server.")}</p>}
      {request && loading && <p role="status">{t("Checking AI settings…")}</p>}
      {request && error && <p role="alert">{translateRequestMessage(t, error)} <button onClick={() => void refreshAiState(request)}>{t("Retry")}</button></p>}
      {request && settings?.config.configured && (
        <PreferenceForm
          key={JSON.stringify(settings.preferences)}
          request={request}
          t={t}
          config={settings.config}
          preferences={settings.preferences}
        />
      )}
    </section>
  );
}

function PreferenceForm({ request, config, preferences, t }: {
  request: DiscoveryRequest;
  config: AiConfig;
  preferences: Preferences;
  t: Translate;
}) {
  const [draft, setDraft] = useState(preferences);
  const { run, busy, error } = useAiOperation(request, "preferences");
  const retired = missingProvider(config, preferences);
  const profilesAvailable = config.progress_supported !== false;
  const providers = (config.providers ?? []).filter((provider) => provider.id !== "default");
  const validLanguage = draft.style.language === "auto" || /^[a-z]{2}$/.test(draft.style.language);

  function save() {
    const body = {
      ai_enabled: draft.ai_enabled, style: draft.style,
      provider_id: draft.provider_id !== preferences.provider_id ? draft.provider_id : undefined,
    };
    void run(
      (signal) => discoveryJson<Preferences>(request, "/ai/v1/preferences", jsonBody("PUT", body, signal)),
      (updated) => {
        applyAiPreferences(request, updated);
        void refreshAiState(request);
      },
    );
  }

  return (
    <>
      <p>{t(profilesAvailable
        ? "Book insights use edition metadata and feedback you submit. Reader profiles use eligible reading statistics from your connected library. Quire sends these inputs to the configured AI provider."
        : "Book insights use edition metadata and feedback you submit. Quire sends these inputs to the configured AI provider.")}</p>
      <form onSubmit={(event) => { event.preventDefault(); save(); }}>
        <label>
          <input type="checkbox" checked={draft.ai_enabled} onChange={(event) => setDraft({ ...draft, ai_enabled: event.target.checked })} />
          {t(profilesAvailable ? "Enable AI insights and reader profiles" : "Enable AI insights")}
        </label>
        <label>
          {t("Provider")}
          <select value={draft.provider_id} onChange={(event) => setDraft({ ...draft, provider_id: event.target.value })}>
            <option value="default">{t("Server default")}</option>
            {retired && <option disabled value={preferences.provider_id}>{t("Unavailable provider")}</option>}
            {providers.map((provider) => <option key={provider.id} value={provider.id}>{provider.label} ({provider.model_id})</option>)}
          </select>
        </label>
        {retired && <p role="status">{t("The previously selected provider is no longer available. Select another provider to generate insights.")}</p>}
        <label>
          {t("Tone")}
          <select value={draft.style.tone} onChange={(event) => setDraft({ ...draft, style: { ...draft.style, tone: event.target.value } })}>
            {Object.entries(toneOptions).map(([value, label]) => <option key={value} value={value}>{t(label)}</option>)}
          </select>
        </label>
        <label>
          {t("Insight language (auto or a two-letter language code)")}
          <input value={draft.style.language} maxLength={4} onChange={(event) => setDraft({ ...draft, style: { ...draft.style, language: event.target.value.toLowerCase() } })} />
        </label>
        <button disabled={busy || !validLanguage || (draft.ai_enabled && missingProvider(config, draft)) || JSON.stringify(draft) === JSON.stringify(preferences)}>
          {t("Save AI settings")}
        </button>
        {error && <p role="alert">{translateRequestMessage(t, error)}</p>}
      </form>
    </>
  );
}

function Recommendations({ title, items, t }: { title: string; items?: Recommendation[]; t: Translate }) {
  if (!items?.length) return null;
  return (
    <section>
      <h4>{title}</h4>
      <ul>
        {items.map((item, index) => {
          const url = publicLink(item.source_url);
          return (
            <li key={index}>
              <strong>{url ? <a href={url} target="_blank" rel="noreferrer">{item.title}</a> : item.title}</strong>
              {item.author && ` ${interpolate(t, "by {author}", { author: item.author })}`}
              <p>{item.rationale}</p>
              <Citations sources={item.sources ?? []} t={t} />
            </li>
          );
        })}
      </ul>
    </section>
  );
}

export function ReaderProfilePanel({ request, aiAvailable, t = english }: AiPanelProps) {
  const { settings } = useAiState(request, aiAvailable);
  if (!request || !settings?.config.configured || settings.config.progress_supported === false) return null;
  return <ProfileControls key={String(settings.preferences.ai_enabled)} request={request} settings={settings} t={t} />;
}

function ProfileControls({ request, settings, t }: {
  request: DiscoveryRequest;
  settings: { config: AiConfig; preferences: Preferences };
  t: Translate;
}) {
  const { run, busy, error, ambiguous, cancel, paid, recover } = useAiOperation(request, "profile");
  const [profile, setProfile] = useState<Profile>();
  const [message, setMessage] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const { preferences, config } = settings;

  function load(refresh: boolean) {
    setMessage("");
    void run(async (signal) => {
      try {
        return await discoveryJson<Profile>(
          request, refresh ? "/ai/v1/profile/refresh" : "/ai/v1/profile",
          { method: refresh ? "POST" : "GET", signal },
        );
      } catch (cause) {
        if (!refresh && isHttpStatus(cause, 404)) return undefined;
        throw cause;
      }
    }, (value) => {
      if (!refresh) recover(value?.generated_at);
      setProfile(value);
      if (!value) setMessage("No reader profile has been saved.");
    }, refresh ? { previous: profile?.generated_at } : undefined);
  }

  function remove() {
    void run((signal) => discoveryJson<void>(request, "/ai/v1/profile", { method: "DELETE", signal }), () => {
      setProfile(undefined);
      setConfirmDelete(false);
      setMessage("Reader profile deleted.");
    });
  }

  return (
    <section className="discovery-panel panel">
      <h3>{t("Reader profile")}</h3>
      <p>{t("Refreshing sends eligible reading statistics to the AI provider and can use your daily profile quota.")}</p>
      {!preferences.ai_enabled && <p>{t("Enable AI in Settings to refresh your profile.")}</p>}
      {preferences.ai_enabled && missingProvider(config, preferences) && <p>{t("Select an available AI provider in Settings.")}</p>}
      <div className="discovery-actions">
        <button disabled={busy} onClick={() => load(false)}>{t("Open saved profile")}</button>
        <button disabled={busy || ambiguous || !preferences.ai_enabled || missingProvider(config, preferences)} onClick={() => load(true)}>{t("Refresh profile")}</button>
        <button disabled={busy} onClick={() => setConfirmDelete(true)}>{t("Delete profile")}</button>
        {busy && <button onClick={cancel}>{t(paid ? "Stop waiting" : "Cancel")}</button>}
      </div>
      {confirmDelete && <div role="group" aria-label={t("Delete reader profile")}>
        <p>{t("Delete your saved reader profile and its recommendations?")}</p>
        <button disabled={busy} onClick={remove}>{t("Delete saved profile")}</button>
        <button disabled={busy} onClick={() => setConfirmDelete(false)}>{t("Keep profile")}</button>
      </div>}
      {busy && <p role="status">{t("Working on reader profile…")}</p>}
      {error && <p role="alert">{translateRequestMessage(t, error)}</p>}
      {message && <p role="status">{t(message)}</p>}
      {profile && (
        <>
          {profile.payload.narrative && <p>{profile.payload.narrative}</p>}
          <Recommendations t={t} title={t("From your library")} items={profile.payload.in_library_recommendations} />
          <Recommendations t={t} title={t("Discovery")} items={profile.payload.discovery_recommendations} />
          <Recommendations t={t} title={t("AI suggestions")} items={profile.payload.ai_suggested_recommendations} />
          <p>{interpolate(t, "Generated by {model} on {date}.", { model: profile.model_id, date: new Date(profile.generated_at).toLocaleDateString(document.documentElement.lang || undefined) })}</p>
        </>
      )}
    </section>
  );
}
