import { interpolate, type Translate } from "./translation";

const patterns: [RegExp, string, string[]][] = [
  [/^You've finished (\d+) book\(s\) by this author\.$/, "You finished {count} book(s) by this author.", ["count"]],
  [/^You've abandoned (\d+) books by this author\.$/, "You abandoned {count} books by this author.", ["count"]],
  [/^Matches your high-finish theme '(.*)' \((\d+) finished, (\d+) abandoned\)\.$/, "Matches a theme you often finish, “{theme}” ({finished} finished, {abandoned} abandoned).", ["theme", "finished", "abandoned"]],
  [/^You've abandoned (\d+) of (\d+) books tagged '(.*)'\.$/, "You abandoned {count} of {total} books tagged “{theme}”.", ["count", "total", "theme"]],
  [/^Language matches most of your reading: (.+)\.$/, "Matches the language you read most, {language}.", ["language"]],
  [/^You mostly read in (.+)\.$/, "You mostly read in {language}.", ["language"]],
];

export function affinityReason(message: string, t: Translate): string {
  for (const [pattern, label, names] of patterns) {
    const match = pattern.exec(message);
    if (match) return interpolate(t, label, Object.fromEntries(names.map((name, index) => [name, match[index + 1]])));
  }
  return t(message);
}
