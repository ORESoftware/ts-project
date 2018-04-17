

import chalk from 'chalk';

export const log = {
  info: console.log.bind(console, chalk.bold.cyan.underline('ts_project:')),
  error: console.error.bind(console, chalk.magenta.bold.underline('ts_project error:'))
};