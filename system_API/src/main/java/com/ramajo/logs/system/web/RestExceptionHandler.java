package com.ramajo.logs.system.web;

import com.ramajo.logs.system.exceptions.CargaIndisponivelException;
import com.ramajo.logs.system.exceptions.CargaInativaException;
import com.ramajo.logs.system.exceptions.CargaNaoVinculadaException;
import com.ramajo.logs.system.exceptions.DominioException;
import com.ramajo.logs.system.exceptions.OperadorEmUsoException;
import com.ramajo.logs.system.exceptions.OperadorInativoException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
import com.ramajo.logs.system.exceptions.PassoJaFinalizadoException;
import com.ramajo.logs.system.exceptions.PeriodoInvalidoException;
import com.ramajo.logs.system.exceptions.PosicaoIncompativelException;
import com.ramajo.logs.system.exceptions.ProcessoEmUsoException;
import com.ramajo.logs.system.exceptions.ProcessoInativoException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Traduz exceções em respostas HTTP — a camada de domínio não conhece HTTP,
 * quem mapeia código/tipo em 404/409/422 é aqui (ver DominioException).
 *
 * Regra de status:
 *   - recurso inexistente ...................... 404 NOT FOUND
 *   - conflito de ESTADO (já finalizada, carga
 *     em outra OS, passo já fechado, corrida
 *     de lote no índice único, processo ainda
 *     configurado como entrada de setor, operador
 *     com histórico ou último admin) ........... 409 CONFLICT
 *   - requisição semanticamente inválida
 *     (operador/carga/processo inativo, carga
 *     não vinculada, regra genérica) ........... 422 UNPROCESSABLE ENTITY
 *   - falha de validação de entrada (@Valid, query
 *     param ausente/malformado, período que não
 *     fecha) .................................... 400 BAD REQUEST
 */
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<ApiError> naoEncontrado(RecursoNaoEncontradoException ex,
                                                  HttpServletRequest req) {
        return build(ex, HttpStatus.NOT_FOUND, req);
    }

    @ExceptionHandler({
            OrdemForaDeCirculacaoException.class,
            CargaIndisponivelException.class,
            PassoJaFinalizadoException.class,
            ProcessoEmUsoException.class,
            OperadorEmUsoException.class})
    public ResponseEntity<ApiError> conflito(DominioException ex, HttpServletRequest req) {
        return build(ex, HttpStatus.CONFLICT, req);
    }

    @ExceptionHandler({
            CargaInativaException.class,
            CargaNaoVinculadaException.class,
            OperadorInativoException.class,
            PosicaoIncompativelException.class,
            ProcessoInativoException.class})
    public ResponseEntity<ApiError> naoProcessavel(DominioException ex, HttpServletRequest req) {
        return build(ex, HttpStatus.UNPROCESSABLE_ENTITY, req);
    }

    /**
     * Período que não fecha é entrada malformada, não conflito de estado — sem
     * este handler explícito cairia como 422 na rede de segurança abaixo.
     */
    @ExceptionHandler(PeriodoInvalidoException.class)
    public ResponseEntity<ApiError> periodoInvalido(PeriodoInvalidoException ex,
                                                    HttpServletRequest req) {
        return build(ex, HttpStatus.BAD_REQUEST, req);
    }

    /** Rede de segurança: qualquer DominioException nova cai aqui como 422. */
    @ExceptionHandler(DominioException.class)
    public ResponseEntity<ApiError> dominio(DominioException ex, HttpServletRequest req) {
        return build(ex, HttpStatus.UNPROCESSABLE_ENTITY, req);
    }

    /**
     * A corrida em finalizarLote (dois lotes abertos) é barrada pelo índice
     * parcial ux_lotes_os_aberto e chega aqui como violação de integridade —
     * traduzimos para 409 com código estável em vez de vazar um 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integridade(DataIntegrityViolationException ex,
                                                HttpServletRequest req) {
        ApiError body = ApiError.of(
                "CONFLITO_DE_INTEGRIDADE",
                "A operação conflitou com o estado atual do recurso. Recarregue e tente de novo.",
                HttpStatus.CONFLICT.value(),
                req.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validacao(MethodArgumentNotValidException ex,
                                             HttpServletRequest req) {
        List<ApiError.CampoInvalido> campos = ex.getBindingResult().getFieldErrors().stream()
                .map(this::campo)
                .toList();
        ApiError body = ApiError.of(
                "VALIDACAO_FALHOU",
                "Um ou mais campos são inválidos.",
                HttpStatus.BAD_REQUEST.value(),
                req.getRequestURI(),
                campos);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Query param que não converte (?dataInicio=abc). Sem isto o Spring devolve
     * um corpo próprio, fora do contrato `codigo`/`mensagem` que o front espera.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> parametroInvalido(MethodArgumentTypeMismatchException ex,
                                                      HttpServletRequest req) {
        ApiError body = ApiError.of(
                "PARAMETRO_INVALIDO",
                "O parâmetro '" + ex.getName() + "' tem valor inválido.",
                HttpStatus.BAD_REQUEST.value(),
                req.getRequestURI(),
                List.of(new ApiError.CampoInvalido(ex.getName(), "valor inválido")));
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> parametroObrigatorio(MissingServletRequestParameterException ex,
                                                         HttpServletRequest req) {
        ApiError body = ApiError.of(
                "PARAMETRO_OBRIGATORIO",
                "O parâmetro '" + ex.getParameterName() + "' é obrigatório.",
                HttpStatus.BAD_REQUEST.value(),
                req.getRequestURI(),
                List.of(new ApiError.CampoInvalido(ex.getParameterName(), "obrigatório")));
        return ResponseEntity.badRequest().body(body);
    }

    private ApiError.CampoInvalido campo(FieldError fe) {
        String msg = fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "inválido";
        return new ApiError.CampoInvalido(fe.getField(), msg);
    }

    private ResponseEntity<ApiError> build(DominioException ex, HttpStatus status,
                                          HttpServletRequest req) {
        ApiError body = ApiError.of(
                ex.getCodigo(), ex.getMessage(), status.value(), req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
