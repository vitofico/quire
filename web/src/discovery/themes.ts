import type { Translate } from "./translation";

/**
 * English names of the theme tags the server attaches to book insights, its controlled
 * vocabulary in server/quire_server/core/ai/themes.py. The tags stay English so they can be
 * counted across books; labels.ts translates the names.
 */
export const themeNames: Record<string, string> = {
  science_fiction: "Science fiction",
  fantasy: "Fantasy",
  literary_fiction: "Literary fiction",
  contemporary_fiction: "Contemporary fiction",
  historical_fiction: "Historical fiction",
  young_adult: "Young adult",
  middle_grade: "Middle grade",
  children: "Children’s",
  poetry: "Poetry",
  drama: "Drama",
  short_stories: "Short stories",
  graphic_novel: "Graphic novel",
  dystopia: "Dystopia",
  post_apocalyptic: "Post-apocalyptic",
  cyberpunk: "Cyberpunk",
  space_opera: "Space opera",
  first_contact: "First contact",
  time_travel: "Time travel",
  alternate_history: "Alternate history",
  magical_realism: "Magical realism",
  epic_fantasy: "Epic fantasy",
  urban_fantasy: "Urban fantasy",
  mythology: "Mythology",
  superheroes: "Superheroes",
  mystery: "Mystery",
  thriller: "Thriller",
  noir: "Noir",
  horror: "Horror",
  romance: "Romance",
  crime: "Crime",
  coming_of_age: "Coming of age",
  war_fiction: "War fiction",
  adventure: "Adventure",
  satire: "Satire",
  western: "Western",
  biography: "Biography",
  memoir: "Memoir",
  history: "History",
  science: "Science",
  philosophy: "Philosophy",
  essays: "Essays",
  journalism: "Journalism",
  business: "Business",
  economics: "Economics",
  psychology: "Psychology",
  self_help: "Self-help",
  travel: "Travel",
  cooking: "Cooking",
  politics: "Politics",
  religion: "Religion",
  true_crime: "True crime",
  nature: "Nature",
  art: "Art",
  music: "Music",
  sports: "Sports",
  education: "Education",
  technology: "Technology",
  medicine: "Medicine",
  health: "Health",
};

/** A theme tag in the reader's language; a tag outside the vocabulary reads as itself. */
export function themeLabel(tag: string, t: Translate): string {
  // Library subjects ("science fiction", "self-help") name the same themes as the tags.
  const name = themeNames[tag.trim().toLowerCase().replace(/[\s-]+/g, "_")];
  return name ? t(name) : tag.replace(/_/g, " ").replace(/^./, (first) => first.toUpperCase());
}
