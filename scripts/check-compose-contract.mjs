import { readFileSync } from "node:fs";
import { join } from "node:path";

const compose = readFileSync(join(process.cwd(), "docker-compose.yml"), "utf8");

const requiredServices = [
  "agentctl-api",
  "agentctl-worker",
  "agentctl-agent-worker",
  "agentctl-web",
  "temporal",
  "postgres",
  "minio",
  "ollama",
  "otel-collector",
  "prometheus",
  "tempo",
  "grafana",
];

const missing = requiredServices.filter(
  (service) => !compose.includes(`  ${service}:`),
);

if (missing.length > 0) {
  console.error(`Compose contract missing services: ${missing.join(", ")}`);
  process.exit(1);
}
