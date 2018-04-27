#!/usr/bin/env node
'use strict';
Object.defineProperty(exports, "__esModule", { value: true });
const path = require("path");
const cp = require("child_process");
const readline = require("readline");
const os = require("os");
const async = require("async");
const dashdash = require('dashdash');
const logger_1 = require("./logger");
const cli_options_1 = require("./cli-options");
const chalk_1 = require("chalk");
const opts = dashdash.parse({ options: cli_options_1.cliOptions });
const isForce = Boolean(opts.force || false);
const isYes = Boolean(opts.yes || false);
const pth = String(opts.name || opts._args[0] || '').trim();
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
    confirm: function (cb) {
        if (isForce || isYes) {
            logger_1.log.info('No confirmation needed.');
            return process.nextTick(cb);
        }
        logger_1.log.info('Please confirm.');
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
                logger_1.log.error('Next time you need to confirm with an affirmative.');
                process.exit(1);
            }
        });
    },
    mkdirp: function (confirm, cb) {
        logger_1.log.info('Creating directory, with make derp.');
        const k = cp.spawn('bash');
        k.stdin.end(`mkdir -p ${projRoot};\n`);
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`The following command failed: "mkdir -p ${projRoot}"`), null);
            }
            else {
                logger_1.log.info('Successfully made derp.');
                cb(null, null);
            }
        });
    },
    clone: function (confirm, mkdirp, cb) {
        logger_1.log.info('Cloning repo.');
        const k = cp.spawn('bash', [], { cwd: projRoot });
        k.stdin.end(`git clone --depth=5 --branch=master https://github.com/ORESoftware/typescript-library-skeleton.git ${name};\n`);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`Could not clone project to directory ${proj}`), null);
            }
            else {
                logger_1.log.info('Git clone succeeded.');
                cb(null, null);
            }
        });
    },
    removeGit: function (clone, cb) {
        logger_1.log.info('Removing git remote.');
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end(`rm -rf .git;\n`);
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`The following command failed: 'git remote rm origin', for this repo: ${proj}`), null);
            }
            else {
                logger_1.log.info('Successfully removed git remote.');
                cb(null, null);
            }
        });
    },
    install: function (replaceWithName, clone, cb) {
        logger_1.log.info('Installing NPM deps...');
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end('npm install --silent;\n');
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`npm install failed for "${proj}".`), null);
            }
            else {
                logger_1.log.info('NPM install succeeded.');
                cb(null, null);
            }
        });
    },
    replaceWithName: function (replaceOrgName, clone, cb) {
        logger_1.log.info('Replacing temp library name with your library name.');
        const getXargsCommand = function () {
            return String(os.platform()).toUpperCase() === 'DARWIN' ?
                `xargs sed -i '' s/typescript-library-skeleton/${name}/g` :
                `xargs sed -i s/typescript-library-skeleton/${name}/g`;
        };
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end(`find . -type f -not -path '*/.git/*' | ${getXargsCommand()};\n`);
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`sed/replace command failed for "${proj}".`), null);
            }
            else {
                logger_1.log.info('replace/sed command succeeded.');
                cb(null, null);
            }
        });
    },
    replaceOrgName: function (clone, cb) {
        logger_1.log.info('Replacing org name with temp org name.');
        const getXargsCommand = function () {
            return String(os.platform()).toUpperCase() === 'DARWIN' ?
                `xargs sed -i '' s/ORESoftware/your-org/g` :
                `xargs sed -i s/ORESoftware/your-org/g`;
        };
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end(`find . -type f -not -path '*/.git/*' | ${getXargsCommand()};\n`);
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`sed/replace command failed for "${proj}".`), null);
            }
            else {
                logger_1.log.info('sed/replace org name command succeeded.');
                cb(null, null);
            }
        });
    },
    chmod: function (clone, cb) {
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end(`find scripts -name "*.sh" | xargs chmod u+x; \n`);
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`chmod command failed for "${proj}".`), null);
            }
            else {
                logger_1.log.info('chmod command succeeded.');
                cb(null, null);
            }
        });
    }
}, function (err, results) {
    if (err)
        throw err;
    logger_1.log.info('');
    logger_1.log.info(chalk_1.default.green.bold('Success.'));
    logger_1.log.info(chalk_1.default.bold(`You need to add a remote with "git remote add..."`));
    logger_1.log.info(chalk_1.default.blue.bold(`pwd:`), chalk_1.default.blue(`${process.cwd()}`));
    logger_1.log.info(chalk_1.default.blue.bold(`Go to your project:`), chalk_1.default.blue(`cd ${proj}`));
    logger_1.log.info('');
    setTimeout(function () {
        process.exit(0);
    }, 10);
});
