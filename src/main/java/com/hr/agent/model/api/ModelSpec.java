package com.hr.agent.model.api;

/**
 * 一次模型调用要使用的模型与推理参数。
 *
 * <p>temperature 暂与改造前保持一致（0.3），后续可在此扩展 top_p / max_tokens /
 * 结构化输出开关等，由 L1 接入层统一填充。
 */
public record ModelSpec(String model, double temperature) {

    public static ModelSpec of(String model) {
        return new ModelSpec(model, 0.3);
    }
}
