var exec = require('cordova/exec');

exports.NoseDetectionPlugin = function (success, error) {
    exec(success, error, 'NoseDetectionPlugin', 'startDetection');
};
