/**
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */

(function () {
    var _ = require("lib/lodash");

    exports.setProtectedAttributes = function (security) {
        var modifiedMap = {};
        Object.keys(security.authorization).forEach(function (k) {
            modifiedMap[k] = security.authorization[k];
        });

        // find all of the attributes in the managed object schema associated
        // with the current component which have been declared as isProtected: true
        modifiedMap.protectedAttributeList =
            _.chain(
                _.find(openidm.read("config/managed").objects, function (object) {
                    return modifiedMap.component === "managed/" + object.name;
                }).schema.properties
            )
            .pairs()
            .map(function (property) {
                var propertyName = property[0],
                    propertySchema = property[1];
                if (propertySchema.isProtected) {
                    return propertyName;
                } else {
                    return undefined;
                }
            })
            .filter()
            .value();

        security.authorization = modifiedMap;
        return security;
    };

}());
