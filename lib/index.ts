#!/usr/bin/env node
'use strict';

//core
import path = require('path');
import cp = require('child_process');
import readline = require('readline');
import os = require('os');

//npm
import async = require('async');
import {AsyncAutoTaskFunction} from "async";
const dashdash = require('dashdash');

//project
import {log} from './logger';
import {cliOptions} from "./cli-options";
import chalk from "chalk";
const opts = dashdash.parse({options: cliOptions});
const isForce = Boolean(opts.force || false);
const isYes = Boolean(opts.yes || false);
const pth = String(opts.name || opts._args[0]).trim();

if (!pth) {
  throw new Error('No project name provided at command line.');
}

const proj = path.resolve(process.cwd() + '/' + pth);
const projRoot = path.dirname(proj);
const name = path.basename(proj);

if (!/^[a-z0-9_-]+$/i.test(name)) {
  throw new Error('Project name must be alphanumeric (hyphen or underscore is OK too).');
}

async.autoInject({
    
    confirm: function (cb: AsyncAutoTaskFunction<any, any, any>) {
      
      if (isForce || isYes) {
        log.info('No confirmation needed.');
        return process.nextTick(cb);
      }
      
      log.info('Please confirm.');
      
      const rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout
      });
      
      rl.question(`Do you wish to create your project here: "${proj}" ?  (y/n) ... `, (answer) => {
        rl.close();
        const y = String(answer).trim().toUpperCase();
        if (['JEAH', 'YES', 'YASS', 'YEP', 'Y'].includes(y)) {
          cb(null, null);
        }
        else {
          log.error('Next time you need to confirm with an affirmative.');
          process.exit(1);
        }
      });
      
    },
    
    mkdirp: function (confirm: any, cb: AsyncAutoTaskFunction<any, any, any>) {
      log.info('Creating directory, with make derp.');
      const k = cp.spawn('bash');
      k.stdin.end(`mkdir -p ${projRoot};\n`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          cb(new Error(`The following command failed: "mkdir -p ${projRoot}"`), null);
        }
        else {
          log.info('Successfully made derp.');
          cb(null, null);
        }
      });
    },
    
    clone: function (confirm: any, mkdirp: any, cb: AsyncAutoTaskFunction<any, any, any>) {
      
      log.info('Cloning repo.');
      const k = cp.spawn('bash', [], {cwd: projRoot});
      k.stdin.end(`git clone --depth=5 --branch=master https://github.com/ORESoftware/typescript-library-skeleton.git ${name};\n`);
      k.once('exit', function (code) {
        if (code > 0) {
          cb(new Error(`Could not clone project to directory ${proj}`), null);
        }
        else {
          log.info('Git clone succeeded.');
          cb(null, null);
        }
      });
    },
    
    removeGitRemote: function (clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {
      
      log.info('Removing git remote.');
      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end(`git remote rm origin;\n`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          cb(new Error(`The following command failed: 'git remote rm origin', for this repo: ${proj}`), null);
        }
        else {
          log.info('Successfully removed git remote.');
          cb(null, null);
        }
      });
    },
    
    install: function (replaceWithName: any, clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {
      
      log.info('Installing NPM deps...');
      
      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end('npm install --silent;\n');
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          cb(new Error(`npm install failed for "${proj}".`), null);
        }
        else {
          log.info('NPM install succeeded.');
          cb(null, null);
        }
      });
      
    },
    
    replaceWithName: function (replaceOrgName: any, clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {
      
      // find . -type f | xargs sed -i s^<oldstring>^<newstring>^g
      
      log.info('Replacing temp library name with your library name.');
      
      const getXargsCommand = function () {
        return String(os.platform()).toUpperCase() === 'DARWIN' ?
          `xargs sed -i '' s/typescript-library-skeleton/${name}/g` :
          `xargs sed -i s/typescript-library-skeleton/${name}/g`
      };
      
      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end(`find . -type f -not -path '*/.git/*' | ${getXargsCommand()};\n`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          cb(new Error(`sed/replace command failed for "${proj}".`), null);
        }
        else {
          log.info('replace/sed command succeeded.');
          cb(null, null);
        }
      });
      
    },
    
    replaceOrgName: function (clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {
      
      // find . -type f | xargs sed -i s^<oldstring>^<newstring>^g
      
      log.info('Replacing org name with temp org name.');
      
      const getXargsCommand = function () {
        return String(os.platform()).toUpperCase() === 'DARWIN' ?
          `xargs sed -i '' s/ORESoftware/your-org/g` :
          `xargs sed -i s/ORESoftware/your-org/g`
      };
      
      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end(`find . -type f -not -path '*/.git/*' | ${getXargsCommand()};\n`);
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          cb(new Error(`sed/replace command failed for "${proj}".`), null);
        }
        else {
          log.info('sed/replace org name command succeeded.');
          cb(null, null);
        }
      });
      
    },
    
    chmod: function (clone: any, cb: AsyncAutoTaskFunction<any, any, any>) {
      const k = cp.spawn('bash', [], {cwd: proj});
      k.stdin.end('chmod u+x scripts/travis/*;\n');
      k.stderr.pipe(process.stderr);
      k.once('exit', function (code) {
        if (code > 0) {
          cb(new Error(`chmod command failed for "${proj}".`), null);
        }
        else {
          log.info('chmod command succeeded.');
          cb(null, null);
        }
      });
    }
    
  },
  
  function (err, results) {
    
    if (err) throw err;
    
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