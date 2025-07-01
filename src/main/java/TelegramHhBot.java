import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TelegramHhBot автоматически ищет вакансии на hh.ru и отправляет отклики каждый день.
 */
public class TelegramHhBot extends TelegramLongPollingBot {
    // ========== КОНФИГУРАЦИЯ ==========
    // Имя вашего бота (botusername)
    private static final String BOT_USERNAME = "@HHJavaBot";
    // Токен Telegram-бота, полученный от BotFather
    private static final String BOT_TOKEN = "7917100755:AAGA7uwvX07tgxrOrLGN7Qjg9W-EC2DMRwo";
    // Данные для OAuth клиента hh.ru
    private static final String HH_CLIENT_ID = "IKE82J69IU5B7939M07VI2714HVK8F3MHUH0OCD2H7J3A54FQ9VUT8IRM85NN4MP";
    private static final String HH_CLIENT_SECRET = "O11C5OVJV4CVMQO737785PJPOIKI0HDNVEGGA2RHP8M9PDNI8JUN066O03MSQ46N";
    // ID или URL вашего резюме в hh.ru
    private static final String RESUME_ID = "a72a20edff0eecae160039ed1f464336753277";
    // Ваш chat_id в Telegram для отправки уведомлений
    private static final long CHAT_ID = 631391338;

    // HttpClient для выполнения HTTP-запросов
    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Jackson ObjectMapper для разбора JSON-ответов
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Подключение к SQLite для хранения уже отправленных откликов
    private Connection dbConnection;

    /**
     * Конструктор: создаёт или открывает SQLite-базу и таблицу для sent.
     */
    public TelegramHhBot() {
        try {
            // Подключаемся к локальной базе sent.db
            dbConnection = DriverManager.getConnection("jdbc:sqlite:sent.db");
            try (Statement stmt = dbConnection.createStatement()) {
                // Создаем таблицу sent, если её нет. В ней храним ID вакансий
                stmt.execute("CREATE TABLE IF NOT EXISTS sent (vacancy_id TEXT PRIMARY KEY)");
            }
        } catch (SQLException e) {
            // Логируем ошибку подключения или создания таблицы
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return BOT_USERNAME;  // возвращаем имя бота
    }

    @Override
    public String getBotToken() {
        return BOT_TOKEN;      // возвращаем токен бота
    }

    /**
     * Обрабатывает входящие обновления от Telegram (команды /start и /help).
     */
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            // При команде /start или /help отправляем приветственное сообщение
            if ("/start".equals(text) || "/help".equals(text)) {
                SendMessage msg = new SendMessage();
                msg.setChatId(update.getMessage().getChatId().toString());
                msg.setText("Привет! Я буду искать вакансии и отправлять на них отклики раз в сутки в 10:00.");
                try {
                    execute(msg);  // выполняем отправку сообщения
                } catch (TelegramApiException e) {
                    e.printStackTrace();  // логируем ошибку отправки
                }
            }
        }
    }

    /**
     * Планирует ежедневное выполнение задачи jobCheck() в 10:00 по локальному времени.
     */
    public void scheduleDailyJob() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable task = this::jobCheck;  // ссылка на метод проверки вакансий

        // Определяем время следующего запуска: сегодня в 10:00 или завтра
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime nextRun = now.withHour(10).withMinute(0).withSecond(0).withNano(0);
        if (now.isAfter(nextRun)) {
            // Если текущее время уже прошло 10:00, назначаем на завтра
            nextRun = nextRun.plusDays(1);
        }
        // Вычисляем задержку до nextRun
        long initialDelay = Duration.between(now, nextRun).getSeconds();
        // Период повторения — 1 день в секундах
        long period = TimeUnit.DAYS.toSeconds(1);

        // Планируем выполнение задачи с задержкой initialDelay и периодом period
        scheduler.scheduleAtFixedRate(task, initialDelay, period, TimeUnit.SECONDS);
    }

    /**
     * Основная задача: получает свежие вакансии и отправляет отклики.
     */
    private void jobCheck() {
        System.out.println("Начало проверки вакансий...");
        try {
            // Шаг 1: получаем OAuth-токен
            String token = fetchOAuthToken();
            // Шаг 2: получаем список вакансий, опубликованных сегодня
            JsonNode items = fetchVacancies(token);
            int count = 0;
            // Шаг 3: для каждой вакансии проверяем — отправлен ли уже отклик
            for (JsonNode v : items) {
                String vacancyId = v.get("id").asText();
                if (!isSent(vacancyId)) {
                    // Если нет, пробуем отправить отклик
                    boolean ok = sendApplication(vacancyId, token);
                    if (ok) {
                        // Записываем в базу, чтобы не повторяться
                        markSent(vacancyId);
                        count++;
                        // Формируем текст уведомления
                        String msgText = String.format(
                                "✅ Отклик отправлен: %s\n" +
                                        "Компания: %s\n" +
                                        "Ссылка: %s",
                                v.get("name").asText(),
                                v.get("employer").get("name").asText(),
                                v.get("alternate_url").asText()
                        );
                        // Отправляем уведомление в Telegram
                        sendTelegramMessage(msgText);
                    }
                }
            }
            System.out.printf("Отправлено %d новых откликов\n", count);
        } catch (Exception e) {
            // Логируем любую ошибку выполнения задачи
            e.printStackTrace();
        }
    }

    /**
     * Получает OAuth-токен от hh.ru по client_credentials flow.
     * @return access_token для дальнейших запросов
     */
    private String fetchOAuthToken() throws IOException, InterruptedException {
        // Формируем тело запроса
        String body = String.format(
                "grant_type=client_credentials&client_id=%s&client_secret=%s",
                HH_CLIENT_ID, HH_CLIENT_SECRET
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://hh.ru/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        // Выполняем запрос и получаем ответ в виде строки
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(response.body());
        // Извлекаем поле access_token
        return json.get("access_token").asText();
    }

    /**
     * Получает список вакансий, опубликованных с начала текущего дня.
     * @param token OAuth-токен
     * @return массив JSON-объектов вакансий
     */
    private JsonNode fetchVacancies(String token) throws IOException, InterruptedException {
        // Форматируем дату начала дня в нужном формате
        String dateFrom = ZonedDateTime.now()
                .truncatedTo(ChronoUnit.DAYS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        // Составляем URL с параметрами поиска
        String url = String.format(
                "https://api.hh.ru/vacancies?text=%s&area=%d&per_page=20&date_from=%s",
                "Java+разработчик", 2, dateFrom
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(response.body());
        // Возвращаем массив items
        return json.get("items");
    }

    /**
     * Отправляет отклик на вакансию через API hh.ru.
     * @param vacancyId ID вакансии
     * @param token OAuth-токен
     * @return true, если статус ответа 200 или 201
     */
    private boolean sendApplication(String vacancyId, String token) throws IOException, InterruptedException {
        String url = "https://api.hh.ru/negotiations";
        // Формируем JSON с указанием resume
        String body = String.format("{\"resume_id\": \"%s\", \"vacancy_id\": \"%s\"}", RESUME_ID, vacancyId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "HHJavaBot/1.0 (leshchinskyruslan@gmail.com")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        return status == 200 || status == 201;
    }

    /**
     * Проверяет в базе, был ли уже отправлен отклик на данную вакансию.
     */
    private boolean isSent(String vacancyId) throws SQLException {
        String sql = "SELECT 1 FROM sent WHERE vacancy_id = ?";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, vacancyId);
            ResultSet rs = ps.executeQuery();
            return rs.next();  // true, если запись найдена
        }
    }

    /**
     * Сохраняет ID вакансии в базу, чтобы не отправлять повторный отклик.
     */
    private void markSent(String vacancyId) throws SQLException {
        String sql = "INSERT INTO sent(vacancy_id) VALUES(?)";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, vacancyId);
            ps.executeUpdate();
        }
    }

    /**
     * Отправляет сообщение в Telegram с помощью API бота.
     */
    private void sendTelegramMessage(String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(CHAT_ID));
        msg.setText(text);
        try {
            execute(msg);  // Выполняем отправку
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    /**
     * Точка входа в приложение: регистрируем бота и запускаем планировщик.
     */
    public static void main(String[] args) throws Exception {
        // Инициализация API Telegram
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        // Создаём экземпляр бота
        TelegramHhBot bot = new TelegramHhBot();
        // Регистрируем бота в системе
        botsApi.registerBot(bot);
        // Настраиваем ежедневную задачу
        bot.jobCheck();
        System.out.println("Бот запущен и планировщик установлен");
    }
}

