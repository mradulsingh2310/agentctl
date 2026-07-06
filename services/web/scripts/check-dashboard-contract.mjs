import { readFileSync } from "node:fs";
import { join } from "node:path";

const pagePath = join(process.cwd(), "app", "page.tsx");
const page = readFileSync(pagePath, "utf8");

const requiredCopy = [
  "Runs",
  "Pending approvals",
  "Tool calls",
  "Model usage",
  "Eval gates",
  "Trace links",
  "Temporal is execution truth",
  "No authoritative WAL",
];

const missing = requiredCopy.filter((copy) => !page.includes(copy));

if (missing.length > 0) {
  console.error(`Dashboard contract missing: ${missing.join(", ")}`);
  process.exit(1);
}
