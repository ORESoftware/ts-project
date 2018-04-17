"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.cliOptions = [
    {
        name: 'version',
        type: 'bool',
        help: 'Print tool version and exit.'
    },
    {
        names: ['help', 'h'],
        type: 'bool',
        help: 'Print this help and exit.'
    },
    {
        names: ['verbose', 'v'],
        type: 'arrayOfBool',
        help: 'Verbose output. Use multiple times for more verbose.'
    },
    {
        names: ['name'],
        type: 'string',
        help: 'Name of the project (and residing directory).',
        default: ''
    },
    {
        names: ['force', 'f'],
        type: 'bool',
        help: 'Force everything.',
        default: false
    },
    {
        names: ['yes', 'y'],
        type: 'bool',
        help: 'Say yes to everything.',
        default: false
    },
];
