FROM node:9

RUN apt-get -y update
RUN apt-get -y install sudo
RUN sudo apt-get -y update
RUN apt-get install -y netcat

RUN echo "newuser ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

RUN useradd -ms /bin/bash newuser
USER newuser
RUN mkdir -p /home/newuser/app
WORKDIR /home/newuser/app

RUN sudo chown -R $(whoami) $(npm config get prefix)/lib
RUN sudo chown -R $(whoami) $(npm config get prefix)/lib/node_modules
RUN sudo chown -R $(whoami) $(npm config get prefix)/bin
RUN sudo chown -R $(whoami) $(npm config get prefix)/share
RUN sudo chown -R $(whoami) /usr/local/lib
RUN sudo chown -R $(whoami) /usr/local/etc

RUN npm install -g typescript
RUN npm install -g @oresoftware/waldo

COPY package.json .
RUN npm install --loglevel=warn


ENV PATH "./node_modules/.bin:${PATH}"

RUN echo "our user is $USER";

COPY . .
RUN tsc


ENTRYPOINT ["./test/index.sh"]
#ENTRYPOINT ["r2g"]
