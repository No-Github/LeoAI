package org.leo.core.entity;

/** 一个可直接点击提交给 Agent 的结构化选项。 */
public class AiUserInputOption {

    private String label;
    private String value;
    private String intent;

    public AiUserInputOption() {
    }

    public AiUserInputOption(String label, String value, String intent) {
        this.label = label;
        this.value = value;
        this.intent = intent;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
}
