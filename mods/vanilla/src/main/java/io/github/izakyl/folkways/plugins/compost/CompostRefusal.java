package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public enum CompostRefusal implements RefusalKind.Named {
    NOT_A_STATION,
    STATION_BUSY,
    STATION_CHANGED;

    @Override
    public boolean lasting() { return this == NOT_A_STATION; }
}
