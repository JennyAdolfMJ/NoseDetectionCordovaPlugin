var exec = require('cordova/exec');

exports.NoseDetectionCordovaPlugin = function (success, error) {
    exec(success, error, 'NoseDetectionCordovaPlugin', 'startDetection');
};
