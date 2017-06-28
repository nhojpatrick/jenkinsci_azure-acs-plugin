Behaviour.specify("select[name$=containerService]", 'hide-optional-based-on-orchestrator', 10000, function(select) {
    function handle_change() {
        var value = $(select).getValue();
        var c = findNearBy(select, 'kubernetesNamespace');
        if (c === null) {
            return;
        }
        if (/\|\s*kubernetes$/i.test(value)) {
            $(c).up('tr').show();
        } else {
            $(c).up('tr').hide();
        }
    }

    handle_change();
    $(select).on('change', handle_change);
    $(select).on('click', handle_change);
});
