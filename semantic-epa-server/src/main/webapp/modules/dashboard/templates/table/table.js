'use strict';

angular.module('streamPipesApp')
    .directive('tableWidget', function () {
        return {
            restrict: 'A',
            replace: true,
            templateUrl: 'modules/dashboard/templates/table/table.html',
            scope: {
                data: '='
            },
            controller: function ($scope) {
                $scope.tableOptions = {
                    initialSorts: [
                        { id: 'value', dir: '-' }
                    ]
                };
                $scope.columns = [
                    { id: 'randomString', key: 'randomString', label: 'randomString' },
                    { id: 'randomValue', key: 'randomValue', label: 'randomValue', sort: 'randomValue' }
                ];
            },
            link: function postLink(scope) {
                scope.$watch('data', function (data) {
                    if (data) {
                        scope.items = data;
                    }
                });
            }
        };
    });