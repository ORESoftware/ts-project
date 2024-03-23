#!/usr/bin/env node
'use strict';

//core
import path = require('path');
import cp = require('child_process');
import readline = require('readline');
import os = require('os');
import fs = require('fs');

//npm
import async = require('async');
import {AsyncAutoTaskFunction} from "async";
const dashdash = require('dashdash');
const {getCleanTrace} = require('clean-trace');

//project
import {log} from './logger';
import {cliOptions} from "./cli-options";
import chalk from "chalk";
const opts = dashdash.parse({options: cliOptions});
const isForce = Boolean(opts.force || false);
const isYes = Boolean(opts.yes || false);
const pth = String(opts.name || opts._args[0] || '').trim();

if (!pth) {
  throw chalk.magenta.bold('No project name/path provided at command line. Instead, use "$ tsproject x/y/z" ...');
}

const proj = path.resolve(process.cwd() + '/' + pth);
const projRoot = path.dirname(proj);
const name = path.basename(proj);

if (!/^[.a-z0-9_-]+$/i.test(name)) {
  throw new Error(chalk.magenta('Project name must be alphanumeric (hyphen, underscore and period, is OK too).'));
}

const shared = {
  packageExists: null as Boolean
};

async.autoInject({

    npmView (cb: AsyncAutoTaskFunction<any, any, any>) {

      log.info(`Running background process to check if NPM package with name '${name}' already exists...`);
      
      const k = cp.spawn('bash');
      k.stdin.end(`(npm view '${name}' &> /dev/null) &> /dev/null;`);

      let stderr = '';
      k.stderr.on('data', function (d) {
        stderr += String(d || '');
      });
      
      k.stdout.pipe(fs.createWriteStream('/dev/null'));
      k.stderr.pipe(fs.createWriteStream('/dev/null'))

      const r = /is not in the npm registry/;
      k.once('exit', function (code) {
        if (stderr && !r.test(stderr)) {
          log.error('\n', stderr);
        }

        shared.packageExists = code === 0;
        cb(null, 0 as any);
      });
    },

    confirm (cb: AsyncAutoTaskFunction<any, any, any>) {

      if (isForce || isYes) {
        log.info('No confirmation needed.');
        return process.nextTick(cb);
      }

      fs.stat(proj, function (err) {

        if (!err) {
          log.error('Something already exists at path:', proj);
          log.error('Refusing to overwrite.');
          return process.exit(1);
        }

        log.warn('Please respond to the following prompt:');

        const rl = readline.createInterface({
          input: process.stdin,
          output: process.stdout
        });

        const q = `Do you wish to create your project here: '${chalk.cyan(proj)}' ?  (y/n) ... `;

        rl.question(chalk.blueBright.bold(q), (answer) => {
          rl.close();
          const y = String(answer || '').trim().toUpperCase();
          if (['JEAH', 'YES', 'YASS', 'YEP', 'Y'].includes(y)) {
            if (shared.packageExists === null) {
              log.info('Still checking to see if the NPM package name exists already...');
            }
            return cb(null, null);
          }

          log.error('Next time you need to confirm with an affirmative.');
          process.exit(1);

        });

      });

    },

    confirmAndnpmView (confirm: any, npmView: number, cb: AsyncAutoTaskFunction<any, any, any>) {

      if (!shared.packageExists) {
        log.info(`Package with '${name}' does not appear to already exist on NPM.`);
        return process.nextTick(cb);
      }

      log.warn('Please respond to the following prompt:');

      const rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout
      });

      const q = `It appears that the project name you may want ('${chalk.red(name)}') is already taken on NPM, continue anyway?  (y/n) ... `;

      rl.question(chalk.blueBright.bold(q), (answer) => {
        rl.close();
        const y = String(answer).trim().toUpperCase();
        if (['JEAH', 'YES', 'YASS', 'YEP', 'Y'].includes(y)) {
          return cb(null, null);
        }

        log.error('Next time you need to confirm with an affirmative.');
        process.exit(1);

      });

    },

    mkdirp (confirmAndnpmView: any, cb: AsyncAutoTaskFunction<any, any, any>) {

      log.info('Creating directory, with make derp.');
      const k = cp.spawn('bash');
      k.stdin.end(`mkdir -p ${projRoot};\n`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          return cb(new Error(`The following command failed: "mkdir -p ${projRoot}"`), null);
        }

        log.info('Successfully made derp.');
        cb(null, null);

      });
    },

    clone (confirmAndnpmView: any, mkdirp: any, cb: AsyncAutoTaskFunction<any, any, any>) {

      log.info('Cloning repo.');
      const k = cp.spawn('bash', [], {cwd: projRoot});
      k.stdin.end(`git clone --depth=3 --branch=master https://github.com/ORESoftware/typescript-library-skeleton.git '${name}';\n`);
      k.once('exit', function (code) {
        if (code > 0) {
          return cb(new Error(`Could not clone project to directory '${proj}'`), null);
        }

        log.info('Git clone succeeded.');
        cb(null, null);

      });
    },

    removeGit (confirmAndnpmView: any, clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {

      log.info('Removing git remote.');
      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end(`rm -rf .git;`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          return cb(new Error(`The following command failed: 'git remote rm origin', for this repo: '${proj}'`), null);
        }

        log.info('Successfully removed git remote.');
        cb(null, null);

      });
    },

    install (confirmAndnpmView: any, clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {

      log.info('Installing NPM deps...');

      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end('set -e; npm install --silent;');
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          return cb(new Error(`npm install failed for "${proj}".`), null);
        }

        log.info('NPM install succeeded.');
        cb(null, null);

      });

    },

    replaceWithName (replaceOrgName: any, install: any, clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {

      // find . -type f | xargs sed -i s^<oldstring>^<newstring>^g
      return process.nextTick(cb);

      log.info('Replacing temp library name with your library name.');

      const getXargsCommand = function () {
        return String(os.platform()).toUpperCase() === 'DARWIN' ?
          `xargs sed -i '' s/typescript-library-skeleton/${name}/g` :
          `xargs sed -i s/typescript-library-skeleton/${name}/g`
      };

      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end(`set -e; waldo -n "/node_modules/" -n "/.git/" | ${getXargsCommand()};\n`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          return cb(new Error(`sed/replace command failed for "${proj}".`), null);
        }

        log.info('replace/sed command succeeded.');
        cb(null, null);

      });

    },

    replaceOrgName (confirmAndnpmView: any, install: any, clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {

      // find . -type f | xargs sed -i s^<oldstring>^<newstring>^g

      return process.nextTick(cb);

      log.info('Replacing org name with temp org name.');

      const getXargsCommand = function () {
        return String(os.platform()).toUpperCase() === 'DARWIN' ?
          `xargs sed -i '' s/ORESoftware/your-org/g` :
          `xargs sed -i s/ORESoftware/your-org/g`
      };

      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end(`set -e; waldo -n "/node_modules/" -n "/.git/" | ${getXargsCommand()};\n`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          return cb(new Error(`sed/replace command failed for "${proj}".`), null);
        }

        log.info('sed/replace org name command succeeded.');
        cb(null, null);
      });

    },

    chmod (confirmAndnpmView: any, clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {

      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end(`set -e; find scripts -name "*.sh" | xargs chmod u+x; \n`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          return cb(new Error(`chmod command failed for "${proj}".`), null);
        }

        log.info('chmod command succeeded.');
        cb(null, null);

      });
    }

  },

   (err, results) => {

    if (err) {
      throw getCleanTrace(err);
    }

    log.info('');
    log.info(chalk.green.bold('Success.'));
    log.info(chalk.bold(`You need to add a remote with "git remote add..."`));
    log.info(chalk.blue.bold(`pwd:`), chalk.blue(`${process.cwd()}`));
    log.info(chalk.blue.bold(`Go to your project:`), chalk.blue(`cd ${proj}`));
    log.info('');

    setTimeout(function () {
      process.exit(0);
    }, 10);

  });