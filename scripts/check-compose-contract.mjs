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

const servicesRequiringHealthchecks = [
  "agentctl-api",
  "agentctl-worker",
  "agentctl-agent-worker",
  "agentctl-web",
  "postgres",
  "temporal",
  "minio",
  "otel-collector",
];

const missingHealthchecks = servicesRequiringHealthchecks.filter((service) => {
  const serviceStart = compose.indexOf(`  ${service}:`);
  if (serviceStart < 0) {
    return true;
  }

  const nextServiceMatch = compose
    .slice(serviceStart + service.length + 4)
    .match(/\n  [a-zA-Z0-9_-]+:\n/);
  const nextService =
    nextServiceMatch === null
      ? -1
      : serviceStart + service.length + 4 + nextServiceMatch.index;
  const serviceBlock = compose.slice(
    serviceStart,
    nextService === -1 ? compose.length : nextService,
  );
  return !serviceBlock.includes("\n    healthcheck:");
});

if (missingHealthchecks.length > 0) {
  console.error(
    `Compose contract missing healthchecks: ${missingHealthchecks.join(", ")}`,
  );
  process.exit(1);
}
