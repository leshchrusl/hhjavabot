import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    // Файл, в котором храним актуальные access и refresh токены
    private static final String TOKENS_FILE = "tokens.json";

    // Текущие значения токенов
    private String accessToken;
    private String refreshToken;

    // HttpClient для выполнения HTTP-запросов
    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Jackson ObjectMapper для разбора JSON-ответов
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Подключение к SQLite для хранения уже отправленных откликов
    private Connection dbConnection;
    private static final Logger logger = LoggerFactory.getLogger(TelegramHhBot.class);
    // ========== КОНЕЦ КОНФИГУРАЦИИ ==========

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
            loadTokens();
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
                try {
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
                } catch (Exception e) {
                   logger.info(e.getMessage());
                }
            }
            System.out.printf("Отправлено %d новых откликов\n", count);
        } catch (Exception e) {
            // Логируем любую ошибку выполнения задачи
            e.printStackTrace();
        }
    }

    /**
     * Получает OAuth-токен hh.ru, используя сохранённый refresh_token.
     * Для работы необходимо один раз получить refresh_token по authorization code flow
     * и сохранить его в файл tokens.json.
     *
     * После каждого обновления новая пара токенов сохраняется в файл.
     */
    private String fetchOAuthToken() throws IOException, InterruptedException {
        if (refreshToken == null) {
            throw new IllegalStateException("Refresh token not found. Create tokens.json with refresh_token");
        }
        String body = String.format(
                "grant_type=refresh_token&refresh_token=%s&client_id=%s&client_secret=%s",
                refreshToken, HH_CLIENT_ID, HH_CLIENT_SECRET
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hh.ru/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(response.body());

        final JsonNode accessTokenNode = json.get("access_token");
        final JsonNode refreshTokenNode = json.get("refresh_token");

        if (accessTokenNode != null) {
            accessToken = accessTokenNode.asText();
        }
        if (refreshTokenNode != null) {
            refreshToken = refreshTokenNode.asText();
        }
        saveTokens();
        return accessToken;
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
                "https://api.hh.ru/vacancies?text=%s&search_field=name&area=%d&",
                "Java", 2
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
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + token);
            post.setHeader("User-Agent", "HHJavaBot/1.0 (leshchinskyruslan@gmail.com)");

            var builder = MultipartEntityBuilder.create();
            builder.addTextBody("vacancy_id", vacancyId);
            builder.addTextBody("resume_id", RESUME_ID);

            post.setEntity(builder.build());

            try (CloseableHttpResponse response = client.execute(post)) {
                int status = response.getStatusLine().getStatusCode();
                return status == 200 || status == 201;
            }
        }
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
     * Загружает сохранённые access и refresh токены из файла.
     */
    private void loadTokens() {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(TOKENS_FILE);
            if (java.nio.file.Files.exists(path)) {
                JsonNode node = objectMapper.readTree(java.nio.file.Files.readString(path));
                accessToken = node.path("access_token").asText(null);
                refreshToken = node.path("refresh_token").asText(null);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Сохраняет текущие access и refresh токены в файл.
     */
    private void saveTokens() {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.put("access_token", accessToken);
            node.put("refresh_token", refreshToken);
            java.nio.file.Files.writeString(
                    java.nio.file.Paths.get(TOKENS_FILE),
                    node.toPrettyString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            e.printStackTrace();
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
        logger.info("Запуск TelegramHhBot...");
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

