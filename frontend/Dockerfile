FROM node:24.13.0-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.28.0-alpine
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
