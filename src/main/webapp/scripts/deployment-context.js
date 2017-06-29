/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

Behaviour.specify("select[name$=containerService]", 'hide-optional-based-on-orchestrator', 10000, function(select) {
    function handleChange() {
        var value = $(select).getValue();

        var isKubernetes = /\|\s*kubernetes$/i.test(value);
        var isSwarm = /\|\s*swarm$/i.test(value);

        setElementVisibility('kubernetesNamespace', isKubernetes);
        setElementVisibility('swarmRemoveContainersFirst', isSwarm);
    }

    function setElementVisibility(name, show) {
        var c = findNearBy(select, name);
        if (c === null) {
            return;
        }

        if (show) {
            $(c).up('tr').show();
        } else {
            $(c).up('tr').hide();
        }
    }

    handleChange();
    $(select).on('change', handleChange);
    $(select).on('click', handleChange);
});
