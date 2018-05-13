FROM node:9

WORKDIR /app

RUN npm install -g typescript

COPY package.json .
RUN npm install --no-optional --silent

ENV PATH "./node_modules/.bin:${PATH}"

COPY . .
RUN tsc

ENTRYPOINT ["r2g"]
