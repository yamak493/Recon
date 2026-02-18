package net.enabify.recon.execution;

/**
 * コマンド実行結果
 * プラットフォーム非依存の結果データクラス
 */
public class ExecutionResult {
    public final boolean success;
    public final String response;
    public final String plainResponse;
    public final String error;

    public ExecutionResult(boolean success, String response, String plainResponse, String error) {
        this.success = success;
        this.response = response;
        this.plainResponse = plainResponse;
        this.error = error;
    }
}
