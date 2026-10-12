package com.ledgerguard.copilot;

/** 계획을 실행할 수 없음 (미래 기간, 없는 계정과목 등). 질문은 FAILED가 되고 사람이 해석을 고쳐 다시 조회할 수 있다. */
public class PlanException extends RuntimeException {

    public PlanException(String message) {
        super(message);
    }
}
