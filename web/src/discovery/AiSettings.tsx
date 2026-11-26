import { english, interpolate, type Translate } from "./translation";
import { useEffect, useRef, useState } from "react";
import { Setting, SwitchSetting } from "../settings/Settings";
import {
  type AiConfig, type DiscoveryRequest, type Preferences, type Profile, type Recommendation,
  discoveryJson, isHttpStatus, jsonBody, publicLink, requestMessage, translateRequestMessage,
} from "./api";
import { applyAiPreferences, missingProvider, refreshAiState, useAiState } from "./aiState";
import { useAiOperation } from "./aiOperation";
import { type AiPanelProps, Citations } from "./Insights";

const toneOptions = { neutral: "Neutral", enthusiastic: "Enthusiastic", scholarly: "Scholarly", casual: "Casual" };
/** The languages the Android app offers for insights, each named in itself. */
export const insightLanguages: Record<string, string> = {
  auto: "Auto", en: "English", zh: "中文", hi: "हिन्दी", es: "Español", ar: "العربية", fr: "Français",
  pt: "Português", ru: "Русский", ja: "日本語", de: "Deutsch", ko: "한국어", it: "Italiano", tr: "Türkçe",
  pl: "Polski", nl: "Nederlands", uk: "Українська", sv: "Svenska", vi: "Tiếng Việt", id: "Bahasa Indonesia",
};

/** AI features for the signed-in account: the same account preferences the Android app reads and writes. */
export function AiSettingsPanel({ request, aiAvailable, t = english }: AiPanelProps) {
  const { settings, error } = useAiState(request, aiAvailable);
  if (!request) return <p>{t("Connect an account to manage AI features.")}</p>;
  if (aiAvailable === false || settings?.config.configured === false) return <p>{t("AI is unavailable on this server.")}</p>;
  if (error && !settings) {
    return <p role="alert">{translateRequestMessage(t, error)} <button type="button" onClick={() => void refreshAiState(request)}>{t("Retry")}</button></p>;
  }
  if (!settings) return <p role="status" className="hint">{t("Checking AI settings…")}</p>;
  return <PreferenceForm request={request} t={t} config={settings.config} preferences={settings.preferences} />;
}

/** Each change is saved at once; one the server refuses is undone and says so. */
function PreferenceForm({ request, config, preferences, t }: {
  request: DiscoveryRequest;
  config: AiConfig;
  preferences: Preferences;
  t: Translate;
}) {
  const [shown, setShown] = useState(preferences);
  const [failed, setFailed] = useState("");
  // What the server holds: what a refused change goes back to.
  const saved = useRef(preferences);
  const latest = useRef(0);
  // Saves still waiting for an answer: while any is, what shows is the newest change.
  const pending = useRef(0);
  // A change made elsewhere, such as the account answering again, shows here too.
  useEffect(() => {
    if (pending.current) return;
    saved.current = preferences;
    setShown(preferences);
  }, [preferences]);
  const retired = missingProvider(config, shown);
  const profilesAvailable = config.progress_supported !== false;
  const providers = (config.providers ?? []).filter((provider) => provider.id !== "default");
  const language = Object.hasOwn(insightLanguages, shown.style.language) ? shown.style.language : "auto";

  async function save(next: Preferences) {
    const attempt = ++latest.current;
    pending.current++;
    setShown(next);
    setFailed("");
    const body = {
      ai_enabled: next.ai_enabled, style: next.style,
      provider_id: next.provider_id !== saved.current.provider_id ? next.provider_id : undefined,
    };
    try {
      const updated = await discoveryJson<Preferences>(request, "/ai/v1/preferences", jsonBody("PUT", body));
      // Only the answer to the newest change says what the account now holds.
      if (attempt !== latest.current) return;
      saved.current = updated;
      setShown(updated);
      applyAiPreferences(request, updated);
    } catch (cause) {
      if (attempt !== latest.current) return;
      setShown(saved.current);
      setFailed(`${t("Quire could not save this change, so the previous setting is kept.")} ${translateRequestMessage(t, requestMessage(cause))}`);
      // An earlier change may have landed after all: ask the account what it holds.
      void refreshAiState(request);
    } finally {
      pending.current--;
    }
  }

  return (
    <>
      <SwitchSetting
        label={t(profilesAvailable ? "Enable AI insights and reader profiles" : "Enable AI insights")}
        description={t(profilesAvailable
          ? "Book insights use edition metadata and feedback you submit. Reader profiles use eligible reading statistics from your connected library. Quire sends these inputs to the configured AI provider."
          : "Book insights use edition metadata and feedback you submit. Quire sends these inputs to the configured AI provider.")}
        checked={shown.ai_enabled}
        // AI cannot be turned on for a provider the server no longer offers.
        disabled={!shown.ai_enabled && retired}
        onChange={(on) => void save({ ...shown, ai_enabled: on })}
      />
      {providers.length > 0 && (
        <Setting stacked label={t("Provider")} description={t("The AI service that writes for this account.")}>
          {({ id, describedBy }) => (
            <select id={id} aria-describedby={describedBy} value={shown.provider_id}
              onChange={(event) => void save({ ...shown, provider_id: event.target.value })}>
              <option value="default">{t("Server default")}</option>
              {retired && <option disabled value={shown.provider_id}>{t("Unavailable provider")}</option>}
              {providers.map((provider) => <option key={provider.id} value={provider.id}>{provider.label} ({provider.model_id})</option>)}
            </select>
          )}
        </Setting>
      )}
      {retired && <p role="status">{t("The previously selected provider is no longer available. Select another provider to generate insights.")}</p>}
      <Setting label={t("Tone")} description={t("How book insights are written.")}>
        {({ id, describedBy }) => (
          <select id={id} aria-describedby={describedBy} value={shown.style.tone}
            onChange={(event) => void save({ ...shown, style: { ...shown.style, tone: event.target.value } })}>
            {Object.entries(toneOptions).map(([value, label]) => <option key={value} value={value}>{t(label)}</option>)}
          </select>
        )}
      </Setting>
      <Setting label={t("Insight language")} description={t("The language insights are written in. Auto follows the app’s language.")}>
        {({ id, describedBy }) => (
          <select id={id} aria-describedby={describedBy} value={language}
            onChange={(event) => void save({ ...shown, style: { ...shown.style, language: event.target.value } })}>
            {Object.entries(insightLanguages).map(([code, name]) => <option key={code} value={code}>{code === "auto" ? t("Auto") : name}</option>)}
          </select>
        )}
      </Setting>
      {failed && <p role="alert">{failed}</p>}
      {profilesAvailable && <p className="hint">{t("Your reader profile, and deleting it, are in Discover.")} <a href="#/discover">{t("Discover")}</a></p>}
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

/** Says so when the profile is in another language than the reader's insights are now written in. */
function LanguageNote({ written, preferences, t }: { written: string; preferences: Preferences; t: Translate }) {
  const app = document.documentElement.lang || "en";
  const wanted = preferences.style.language === "auto" ? app.split("-")[0] : preferences.style.language;
  if (written === wanted) return null;
  const names = new Intl.DisplayNames([app], { type: "language" });
  return <p>{interpolate(t, "Written in {written}. Refresh to write it in {wanted}.", {
    written: names.of(written) ?? written, wanted: names.of(wanted) ?? wanted,
  })}</p>;
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
  const canRefresh = preferences.ai_enabled && !missingProvider(config, preferences);

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
        <button disabled={busy || ambiguous || !canRefresh} onClick={() => load(true)}>{t("Refresh profile")}</button>
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
          {profile.payload.narrative && canRefresh && <LanguageNote written={profile.payload.language ?? "en"} preferences={preferences} t={t} />}
          <Recommendations t={t} title={t("From your library")} items={profile.payload.in_library_recommendations} />
          <Recommendations t={t} title={t("Discovery")} items={profile.payload.discovery_recommendations} />
          <Recommendations t={t} title={t("AI suggestions")} items={profile.payload.ai_suggested_recommendations} />
          <p>{interpolate(t, "Generated by {model} on {date}.", { model: profile.model_id, date: new Date(profile.generated_at).toLocaleDateString(document.documentElement.lang || undefined) })}</p>
        </>
      )}
    </section>
  );
}
