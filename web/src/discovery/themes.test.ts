import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { translator } from "../language";
import { discoveryLabels } from "./labels";
import { themeLabel, themeNames } from "./themes";

describe("themeLabel", () => {
  it("names every tag of the server's vocabulary, in each app language", () => {
    const source = readFileSync(resolve(process.cwd(), "../server/quire_server/core/ai/themes.py"), "utf8");
    const block = source.split("CONTROLLED_THEMES")[1].split("{")[1].split("}")[0];
    const vocabulary = [...block.matchAll(/"([a-z_]+)"/g)].map((match) => match[1]).sort();
    expect(vocabulary.length).toBeGreaterThan(0);
    expect(Object.keys(themeNames).sort()).toEqual(vocabulary);
    for (const name of Object.values(themeNames)) {
      // A missing label would show the English name, so check the table itself.
      const translations = discoveryLabels[name];
      expect(translations, name).toBeDefined();
      expect(translations.every((label) => label.trim() !== ""), name).toBe(true);
    }
  });

  it("shows a tag in the app's language and an unknown one as itself", () => {
    expect(themeLabel("science_fiction", translator("it"))).toBe("Fantascienza");
    expect(themeLabel("coming_of_age", translator("de"))).toBe("Coming-of-Age");
    expect(themeLabel("self_help", (message) => message)).toBe("Self-help");
    expect(themeLabel("interstellar war", translator("it"))).toBe("Interstellar war");
    // A library subject spelled with spaces or hyphens is the same theme.
    expect(themeLabel("science fiction", translator("it"))).toBe("Fantascienza");
    expect(themeLabel("Self-Help", translator("it"))).toBe(themeLabel("self_help", translator("it")));
  });
});
