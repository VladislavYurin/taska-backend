package ru.taska.aspect;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;


/**
 * Аспект(класс) - отвечает за логику, которая будет происходить при навешивании аннотации.
 * Задача класса - передать в микрометр метрики, которые будут собираться с метода, помеченного нашей аннотацией.
 */
@Aspect
@Slf4j
@RequiredArgsConstructor
public class MetricsAspect {

    /// Центральный реестр (фабрика) и хранилище для всех метрик.
    private final MeterRegistry meterRegistry;

    /// Для доступа к application name
    private final Environment environment;

    ///Создаем Point-Cut - условие для совершения логики (условие - стоит аннотация @TrackMetrics). Advice - метод trackTime, он перехватывает вызов метода, над которым стоит аннотация @TrackMetrics
    @Around("@annotation(trackMetrics)")
    public Object trackMetrics(ProceedingJoinPoint joinPoint,
                               TrackMetrics trackMetrics) throws Throwable {
        //ProceedingJoinPoint нужен для правильной работы c grpc (Around контролирует метод полностью, то есть запрос будет ждать, пока его обработают, а таймер будет работать с момента вызова)

        log.info(
                "ASPECT HIT METHOD = {}",
                joinPoint.getSignature()
        );

        log.debug("Tracking metrics for method : {}", joinPoint.getSignature().getName());

        ///Получаем имена метрик из аннотации
        String counterName = trackMetrics.counter();
        String timerName = trackMetrics.timer();

        ///Получаем имя сервиса
        String applicationName = environment.getProperty("spring.application.name");
        if (applicationName == null || applicationName.isEmpty()) {
            applicationName = "unknown";
        }

        log.debug("Counter: {}, Timer: {}, Application: {}", counterName, timerName, applicationName);

        /// Получаем "холодный" объект Mono/Flux (логика внутри еще НЕ ВЫПОЛНЯЕТСЯ)
        Object result = joinPoint.proceed();

        //result instanceof Mono<?> monoResult - pattern matching в java 21
        if (result instanceof Mono<?> monoResult) {
            ///Начинаем обработку, только когда событие начало обрабатываться (в момент подписки)
            String finalApplicationName = applicationName;
            return Mono.defer(() -> {

                ///Создаем счетчик задач с именем сервиса
                Counter.builder(counterName)
                        .tag("application", finalApplicationName)
                        .register(meterRegistry)
                        .increment();

                /// Запускаем таймер
                Timer.Sample timer = Timer.start(meterRegistry);
                return monoResult
                        .doOnSuccess(r -> stopTimer(timer, timerName, finalApplicationName, "success"))
                        .doOnError(r -> stopTimer(timer, timerName, finalApplicationName, "error"));
            });
        }

        if (result instanceof Flux<?> fluxResult) {

            String finalApplicationName = applicationName;
            return Flux.defer(() -> {

                ///Создаем счетчик задач с именем сервиса
                Counter.builder(counterName)
                        .tag("application", finalApplicationName)
                        .register(meterRegistry)
                        .increment();

                /// Запускаем таймер
                Timer.Sample timer = Timer.start(meterRegistry);
                return fluxResult
                        .doOnComplete(() -> stopTimer(timer, timerName, finalApplicationName, "success"))
                        .doOnError(e -> stopTimer(timer, timerName, finalApplicationName, "error"));
            });
        }
        ///Синхронный случай
        Counter.builder(counterName)
                .tag("application",applicationName)
                .register(meterRegistry)
                .increment();
        Timer.Sample timer = Timer.start(meterRegistry);
        try{
            stopTimer(timer,timerName,applicationName,"success");
            return result;

        }
        finally {
            stopTimer(timer,timerName,applicationName,"error");
        }
    }
    /**
     * Останавливает таймер и регистрирует метрику
     */
    private void stopTimer(Timer.Sample timer, String timerName, String applicationName, String status) {
        timer.stop(Timer.builder(timerName)
                .tag("application", applicationName)
                .tag("status", status)
                .register(meterRegistry));
        log.debug("Timer stopped with status: {}", status);
    }
}
///Можно получать название метода (рефлексией либо через параметры)
///Так же изменить аннотацию: передавать либо название метода + тип протокола
///Либо все параметры получать через рефлексию и делать аннотацию без параметров