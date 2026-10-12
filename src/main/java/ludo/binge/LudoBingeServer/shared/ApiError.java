package ludo.binge.LudoBingeServer.shared;

public class ApiError extends RuntimeException {
    public final int status;
    public final String code;
    public ApiError(int status, String code) { super(code); this.status=status; this.code=code; }
    public static void require(boolean valid, int status, String code) { if(!valid) throw new ApiError(status,code); }
}
