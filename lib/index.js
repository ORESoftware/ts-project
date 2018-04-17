#!/usr/bin/env node
'use strict';
Object.defineProperty(exports, "__esModule", { value: true });
const async = require("async");
const path = require("path");
const cp = require("child_process");
const dashdash = require('dashdash');
const cli_options_1 = require("./cli-options");
const readline = require("readline");
const opts = dashdash.parse({ options: cli_options_1.cliOptions });
const isForce = Boolean(opts.force);
const isYes = opts.yes || '';
const name = String(opts.name || opts._args[0]).trim();
if (!name) {
    throw new Error('No project name provided at command line.');
}
if (!/^[a-z0-9_-]+$/i.test(name)) {
    throw new Error('Project name must be alphanumeric (hyphen or underscore is OK too).');
}
const proj = path.resolve(process.cwd() + '/' + name);
async.autoInject({
    confirm: function (cb) {
        if (isForce || isYes) {
            return process.nextTick(cb);
        }
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
                console.error('Next time you need to confirm with an affirmative.');
                process.exit(1);
            }
        });
    },
    removeGitRemote: function (cb) {
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end(`git remote rm origin;\n`);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`Could not clone project to directory ${proj}`), null);
            }
            else {
                cb(null, null);
            }
        });
    },
    clone: function (confirm, cb) {
        const k = cp.spawn('bash');
        k.stdin.end(`git clone https://github.com/ORESoftware/typescript-library-skeleton.git ${name};\n`);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`Could not clone project to directory ${proj}`), null);
            }
            else {
                cb(null, null);
            }
        });
    },
    install: function (replaceWithName, clone, cb) {
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end('npm install --silent;\n');
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`npm install failed for "${proj}".`), null);
            }
            else {
                console.log('npm install succeeded.');
                cb(null, null);
            }
        });
    },
    replaceWithName: function (replaceOrgName, clone, cb) {
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end(`find . -type f -not -path '*/.git/*' | xargs sed -i '' s/typescript-library-skeleton/${name}/g;\n`);
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`sed/replace command failed for "${proj}".`), null);
            }
            else {
                console.log('replace/sed command succeeded.');
                cb(null, null);
            }
        });
    },
    replaceOrgName: function (clone, cb) {
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end(`find . -type f -not -path '*/.git/*' | xargs sed -i '' s/ORESoftware/your-org/g;\n`);
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`sed/replace command failed for "${proj}".`), null);
            }
            else {
                console.log('sed/replace command succeeded.');
                cb(null, null);
            }
        });
    },
    chmod: function (clone, cb) {
        const k = cp.spawn('bash', [], { cwd: proj });
        k.stdin.end('chmod u+x scripts/travis/*;\n');
        k.stderr.pipe(process.stderr);
        k.once('exit', function (code) {
            if (code > 0) {
                cb(new Error(`chmod command failed for "${proj}".`), null);
            }
            else {
                console.log('chmod command succeeded.');
                cb(null, null);
            }
        });
    }
}, function (err, results) {
    if (err)
        throw err;
    console.log('the results:', results);
    console.log(`You need to add a remote with 'git remote add..."`);
    process.exit(0);
});
