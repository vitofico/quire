import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { translator } from "../language";
import { themeLabel, themeNames } from "./themes";

describe("themeLabel", () => {
  it("names every tag of the server's vocabulary, in each app language", () => {
    const source = readFileSync(resolve(process.cwd(), "../server/quire_server/core/ai/themes.py"), "utf8");
    const vocabulary = [...source.matchAll(/^\s+"([a-z_]+)",/gm)].map((match) => match[1]).sort();
    expect(Object.keys(themeNames).sort()).toEqual(vocabulary);
    for (const lang of ["es", "it", "fr", "de"]) {
      const t = translator(lang);
      for (const name of Object.values(themeNames)) expect(t(name), `${name} in ${lang}`).not.toBe("");
    }
  });

  it("shows a tag in the app's language and an unknown one as itself", () => {
    expect(themeLabel("science_fiction", translator("it"))).toBe("Fantascienza");
    expect(themeLabel("coming_of_age", translator("de"))).toBe("Coming-of-Age");
    expect(themeLabel("self_help", (message) => message)).toBe("Self-help");
    expect(themeLabel("interstellar war", translator("it"))).toBe("Interstellar war");
  });
});
