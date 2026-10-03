package honey.road.swarm;

import java.util.UUID;

/** Service catalog of a virtual branch: the demo seed's services with their share of the flow. */
record Services(UUID id, String code, String name, String prefix, double share) {
    static final Services[] ALL = {
            new Services(UUID.fromString("00000000-0000-4000-8000-000000000001"), "parcel", "Получение посылок", "П", 0.5),
            new Services(UUID.fromString("00000000-0000-4000-8000-000000000004"), "send", "Отправка", "О", 0.3),
            new Services(UUID.fromString("00000000-0000-4000-8000-000000000005"), "finance", "Финансовые услуги", "Ф", 0.2),
    };

    static int pick(double u) {
        double acc = 0;
        for (int i = 0; i < ALL.length; i++) {
            acc += ALL[i].share;
            if (u < acc) { return i; }
        }
        return ALL.length - 1;
    }
}
