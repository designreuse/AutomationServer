package fr.guiet.automationserver.business;

import java.util.List;
import java.util.ArrayList;
import java.util.Calendar;
import org.apache.log4j.Logger;
import fr.guiet.automationserver.dto.*;
import fr.guiet.automationserver.dataaccess.DbManager;

public class Room {

	private static Logger _logger = Logger.getLogger(Room.class);

	private long _id;
	private String _name;
	private Sensor _sensor = null;
	private ArrayList<Heater> _heaterList = new ArrayList<Heater>();
	// private Timer _timer = null;
	private Float _userWantedTemp = null;
	private Float _lastDefaultTemp = null;

	// Retourne la liste des radiateurs de la piece
	public List<Heater> getHeaterList() {
		return _heaterList;
	}

	public long getRoomId() {
		return _id;
	}

	public String getName() {
		return _name;
	}

	public Float getActualHumidity() {
		if (_sensor.HasTimeoutOccured()) {
			return null;
		} else {
			return _sensor.getActualHumidity();
		}
	}

	/**
	 * Calcul de l'heure du prochain changement de temperature
	 */
	public String NextChangeDefaultTemp() {

		DbManager dbManager = new DbManager();
		String result = "NA";
		boolean found = false;
		// Date du jour
		Calendar calendar = Calendar.getInstance();
		// Jour de la semaine
		int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
		int dayOfWeekTemp = calendar.get(Calendar.DAY_OF_WEEK);

		// Temporaire
		Calendar calendarHourBegin = Calendar.getInstance();

		while (!found) {

			TempScheduleDto ts = dbManager.GetNextDefaultTempByRoom(_id, dayOfWeek);

			if (ts != null) {
				found = true;
				calendarHourBegin.setTime(ts.getHourBegin());
				String hour = String.format("%02d:%02d", calendarHourBegin.get(Calendar.HOUR_OF_DAY),
						calendarHourBegin.get(Calendar.MINUTE));
				result = GetDayName(dayOfWeek) + " à " + hour + " (" + String.format("%.2f", ts.getDefaultTempNeeded())
						+ "°C)";
				break;
			}

			if (dayOfWeek == 7)
				dayOfWeek = 1;
			else
				dayOfWeek++;

			if (dayOfWeek == dayOfWeekTemp) {
				_logger.warn(
						"Impossible de déterminer le prochain changement de température par défaut pour les pièces : "
								+ _name);
				result = "NA à NA (NA °C)";
				found = true;
				break;
			}
		}

		return result;
	}

	public Float getActualTemp() {
		if (_sensor.HasTimeoutOccured()) {
			return null;
		} else {
			return _sensor.getActualTemp();
		}
	}

	public Float getWantedTemp() {
		return _userWantedTemp; // Peut etre null
	}

	public synchronized void SetWantedTemp(Float wantedTemp) {
		_logger.info("Modification de la température désirée pour la pièce : " + _name + ". Ancienne valeur : "
				+ _userWantedTemp + ". Nouvelle valeur : " + wantedTemp);
		_userWantedTemp = wantedTemp;
	}

	public boolean AtLeastOneHeaterOn() {
		for (Heater h : _heaterList) {
			if (h.isOn())
				return true;
		}

		return false;
	}

	public boolean IsSensorResponding() {
		return !_sensor.HasTimeoutOccured();
	}

	public boolean IsOffForced() {
		for (Heater h : _heaterList) {
			if (h.IsOffForced())
				return true;
		}

		return false;
	}

	public void ForceExtinctionRadiateurs() {
		for (Heater h : _heaterList) {
			h.ActivateOffForced();
		}
	}

	public void DesactiveExtinctionForceRadiateurs() {
		for (Heater h : _heaterList) {
			h.DesactivateOffForced();
		}
	}

	// Retourne la t° programmée
	public Float GetTempProg() {
		Float currentDefaultTemp = GetDefaultTempByDayAndTime();

		if (currentDefaultTemp == null)
			return null;
		else
			return currentDefaultTemp;
	}

	// Calcul de la temperature desiree pour la piece
	public Float ComputeWantedTemp() {

		Float currentDefaultTemp = GetDefaultTempByDayAndTime();

		// Probleme lors de la recuperation de la temperature par defaut ou
		// utilisateur
		if (currentDefaultTemp == null || _lastDefaultTemp == null || _userWantedTemp == null) {
			// Reinitialisation des temperatures
			_lastDefaultTemp = currentDefaultTemp;
			_userWantedTemp = currentDefaultTemp;
			return null;
		}

		// la temperature par defaut a changer, on reinitialise la temperature
		// voulu par l'utilisateur
		if (currentDefaultTemp.compareTo(_lastDefaultTemp) != 0) {
			_logger.info(currentDefaultTemp + ", " + _lastDefaultTemp
					+ " Réinitialisation de la température désirée par l'utilisateur car il y a un changement de la température par défaut pour la pièce : "
					+ _name);
			_userWantedTemp = currentDefaultTemp;
			_lastDefaultTemp = currentDefaultTemp;
		}

		// si l'utilisateur a modifie la temperature voulue on la retourne
		if (_userWantedTemp.compareTo(currentDefaultTemp) != 0) {
			return _userWantedTemp;
		} else {
			return currentDefaultTemp;
		}
	}

	private Float GetDefaultTempByDayAndTime() {
		DbManager dbManager = new DbManager();
		Float defaultTemp = dbManager.GetDefaultTempByRoom(_id);

		if (defaultTemp == null) {
			_logger.warn("Impossible de déterminer la température par défaut pour les pièces : " + _name
					+ ".Le radiateur ne va pas etre controler correctement...");
		}

		return defaultTemp;
	}

	// Arret du service RoomService
	public void StopService() {
		_sensor.StopService();
	}

	private Room(RoomDto dto, SMSGammuService gammuService, TeleInfoService teleInfoService) {

		_id = dto.id;
		_name = dto.name;

		DbManager dbManager = new DbManager();
		SensorDto sensorDto = dbManager.GetSensorByRoomId(dto.idSensor);

		ArrayList<HeaterDto> heaterDtoList = dbManager.GetHeatersByRoomId(dto.id);
		for (HeaterDto heaterDto : heaterDtoList) {

			Heater heater = Heater.LoadFromDto(heaterDto, this, teleInfoService);
			_heaterList.add(heater);
		}

		// _logger.info("Capteur : " + sensorDto.sensorId + ", address : " +
		// sensorDto.sensorAddress);

		_sensor = Sensor.LoadFromDto(sensorDto, this, gammuService);

		// CreateManageHeaterTask();

		_lastDefaultTemp = GetDefaultTempByDayAndTime();
		_userWantedTemp = _lastDefaultTemp; // temperature par defaut et
											// temperature utilisateur identique
											// au demarrage
	}

	public static Room LoadFromDto(RoomDto dto, SMSGammuService gammuService, TeleInfoService teleInfoService) {

		return new Room(dto, gammuService, teleInfoService);
	}

	private String GetDayName(int day) {

		String dayString = "NA";

		switch (day) {
		case 2:
			dayString = "Lundi";
			break;

		case 3:
			dayString = "Mardi";
			break;

		case 4:
			dayString = "Mercredi";
			break;

		case 5:
			dayString = "Jeudi";
			break;

		case 6:
			dayString = "Vendredi";
			break;

		case 7:
			dayString = "Samedi";
			break;

		case 1:
			dayString = "Dimanche";
			break;
		}

		return dayString;
	}
}
