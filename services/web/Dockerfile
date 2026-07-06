FROM node:26-bookworm-slim AS deps

WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install

FROM node:26-bookworm-slim AS build

WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:26-bookworm-slim

WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app ./
EXPOSE 3000
CMD ["npm", "run", "start"]
