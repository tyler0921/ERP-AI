package com.ledgerguard.copilot;

import com.ledgerguard.reconciliation.TextSimilarity;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 질문·계획의 계정과목 표현을 회사의 수익·비용 계정과목으로 찾는다. 순수 클래스.
 * 순서: 코드 → 이름 → 별칭(광고비 → 광고선전비) → 정규화한 이름이 서로 포함되는 유일한 계정.
 */
public final class AccountResolver {

    /** 흔한 별칭 → 기본 계정과목 이름. ai-service 규칙 Planner와 같은 목록 */
    public static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("광고비", "광고선전비"),
            Map.entry("인건비", "급여"),
            Map.entry("월급", "급여"),
            Map.entry("월세", "임차료"),
            Map.entry("임대료", "임차료"),
            Map.entry("수수료", "지급수수료"),
            Map.entry("택배비", "운반비"),
            Map.entry("배송비", "운반비"),
            Map.entry("식대", "복리후생비"),
            Map.entry("회식비", "복리후생비"),
            Map.entry("사무용품", "소모품비"),
            Map.entry("문구", "소모품비"),
            Map.entry("외주", "외주비"));

    private AccountResolver() {
    }

    public record AccountRef(Long id, String code, String name, String type) {

        public String label() {
            return code + " " + name;
        }
    }

    public static AccountRef resolve(String query, List<AccountRef> accounts) {
        String q = query.trim();
        Optional<AccountRef> found = accounts.stream().filter(a -> a.code().equals(q)).findFirst()
                .or(() -> accounts.stream().filter(a -> a.name().equals(q)).findFirst())
                .or(() -> accounts.stream().filter(a -> a.label().equals(q)).findFirst());
        if (found.isPresent()) {
            return found.get();
        }
        String alias = ALIASES.get(q.replace(" ", ""));
        if (alias != null) {
            found = accounts.stream().filter(a -> a.name().equals(alias)).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
        }
        String nq = TextSimilarity.normalize(q).replaceAll("^\\d+", "");
        if (nq.length() >= 2) {
            List<AccountRef> partial = accounts.stream().filter(a -> {
                String na = TextSimilarity.normalize(a.name());
                return na.equals(nq) || na.contains(nq) || nq.contains(na);
            }).toList();
            if (partial.size() == 1) {
                return partial.get(0);
            }
            if (partial.size() > 1) {
                throw new PlanException("계정과목 '" + q + "'에 해당하는 계정이 여러 개입니다: "
                        + String.join(", ", partial.stream().map(AccountRef::label).toList()));
            }
        }
        throw new PlanException("계정과목 '" + q + "'을(를) 찾을 수 없습니다. 수익·비용 계정과목: "
                + String.join(", ", accounts.stream().map(AccountRef::label).toList()));
    }
}
