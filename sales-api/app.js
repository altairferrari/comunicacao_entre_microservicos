import express from 'express';
import { connectMongoDB } from "./src/config/db/mongoDbConfig.js";
import { createInitialData } from './src/config/db/initialData.js';
import { connectRabbitMq } from './src/config/rabbitmq/rabbitConfig.js';
import checkToken from '../auth-api/src/config/auth/checkToken.js';

const app = express();
const env = process.env;
const PORT = env.PORT || 8082;

connectMongoDB();
createInitialData();
connectRabbitMq();

app.use(checkToken);

app.get("/api/status", async (req, res) => {

    return res.status(200).json({
        service: "Sales-API",
        status: "up",
        httpStatus: 200,
    })
})

app.listen(PORT, () => {
    console.info(`Server started successfullyat port ${PORT}`);
})