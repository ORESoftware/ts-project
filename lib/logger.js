"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const chalk_1 = require("chalk");
exports.log = {
    info: console.log.bind(console, chalk_1.default.bold.cyan.underline('ts_project:')),
    error: console.error.bind(console, chalk_1.default.magenta.bold.underline('ts_project error:'))
};
